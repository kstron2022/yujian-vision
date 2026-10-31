package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 安全基线扫描单元测试：纯 Mockito + 真实 AesCipher(需真实加解密行为), 不起 Spring 上下文/数据库。
 * 轮换场景（docs/10 §1）：legacyAes 非空时, 旧密钥密文迁移到当前密钥;
 * 当前密钥可解/未配置旧密钥/未知密钥三分支均不动数据。
 */
@ExtendWith(MockitoExtension.class)
class SecurityDataSweepTest {

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private GbMediaServerMapper serverMapper;

    @Mock
    private GbCascadeUpperMapper upperMapper;

    private AesCipher aes;

    private SecurityDataSweep task;

    @BeforeEach
    void setUp() {
        aes = new AesCipher("unit-test-aes-key");
        task = new SecurityDataSweep(deviceMapper, serverMapper, upperMapper, aes, null);
    }

    private GbDeviceEntity device(long id, String password) {
        GbDeviceEntity d = new GbDeviceEntity();
        d.setId(id);
        d.setPassword(password);
        return d;
    }

    private GbMediaServerEntity server(long id, String secret) {
        GbMediaServerEntity s = new GbMediaServerEntity();
        s.setId(id);
        s.setSignSecret(secret);
        return s;
    }

    @Test
    @DisplayName("存量明文全部加密回写(密文可解回), 已加密行跳过")
    void encryptsPlaintextAndSkipsEncrypted() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, "12345678"), device(2L, aes.encrypt("99999999"))));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, "vision-zlm-secret")));

        task.sweep();

        ArgumentCaptor<GbDeviceEntity> dc = ArgumentCaptor.forClass(GbDeviceEntity.class);
        verify(deviceMapper, times(1)).updateById(dc.capture());
        assertEquals(1L, dc.getValue().getId());
        assertTrue(AesCipher.isEncrypted(dc.getValue().getPassword()));
        assertEquals("12345678", aes.decrypt(dc.getValue().getPassword()));

        ArgumentCaptor<GbMediaServerEntity> sc = ArgumentCaptor.forClass(GbMediaServerEntity.class);
        verify(serverMapper, times(1)).updateById(sc.capture());
        assertTrue(AesCipher.isEncrypted(sc.getValue().getSignSecret()));
        assertEquals("vision-zlm-secret", aes.decrypt(sc.getValue().getSignSecret()));
    }

    @Test
    @DisplayName("单行回写失败(第 2 行 Data truncation) → 跳过不中止, 后续行与另一张表仍处理")
    void rowFailureSkippedAndOthersContinue() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, "12345678"), device(2L, "23456789"), device(3L, "34567890")));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, "vision-zlm-secret")));
        AtomicInteger seq = new AtomicInteger();
        when(deviceMapper.updateById(any(GbDeviceEntity.class))).thenAnswer(inv -> {
            if (seq.incrementAndGet() == 2) {
                throw new RuntimeException("Data truncation: sign_secret too short");
            }
            return 1;
        });

        assertDoesNotThrow(() -> task.sweep());

        verify(deviceMapper, times(3)).updateById(any(GbDeviceEntity.class));
        ArgumentCaptor<GbDeviceEntity> dc = ArgumentCaptor.forClass(GbDeviceEntity.class);
        verify(deviceMapper, times(3)).updateById(dc.capture());
        assertEquals("34567890", aes.decrypt(dc.getAllValues().get(2).getPassword()));
        verify(serverMapper, times(1)).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("设备表整体失败 → 节点表仍执行, sweep 不抛")
    void deviceTableFailureDoesNotBlockServerTable() {
        when(deviceMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, "vision-zlm-secret")));

        assertDoesNotThrow(() -> task.sweep());

        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        verify(serverMapper, times(1)).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("无明文敏感数据 → 不做任何回写")
    void nothingToDoWhenNoPlaintext() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        when(serverMapper.selectList(any())).thenReturn(List.of());

        task.sweep();

        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("幂等: 已加密回写后重复 sweep 不再产生更新")
    void repeatedSweepIsNoop() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(1L, aes.encrypt("12345678"))));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, aes.encrypt("vision-zlm-secret"))));

        task.sweep();

        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("密钥轮换: 旧密钥密文迁移到当前密钥, 当前密钥密文不动; 迁移结果用当前密钥可解回")
    void rotatesLegacyKeyCiphertexts() {
        AesCipher legacy = new AesCipher("legacy-aes-key");
        SecurityDataSweep rotating = new SecurityDataSweep(deviceMapper, serverMapper, upperMapper, aes, legacy);
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, legacy.encrypt("12345678")), device(2L, aes.encrypt("99999999"))));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, legacy.encrypt("vision-zlm-secret"))));

        rotating.sweep();

        ArgumentCaptor<GbDeviceEntity> dc = ArgumentCaptor.forClass(GbDeviceEntity.class);
        verify(deviceMapper, times(1)).updateById(dc.capture());
        assertEquals(1L, dc.getValue().getId());
        assertEquals("12345678", aes.decrypt(dc.getValue().getPassword()));

        ArgumentCaptor<GbMediaServerEntity> sc = ArgumentCaptor.forClass(GbMediaServerEntity.class);
        verify(serverMapper, times(1)).updateById(sc.capture());
        assertEquals("vision-zlm-secret", aes.decrypt(sc.getValue().getSignSecret()));
    }

    @Test
    @DisplayName("密钥轮换幂等: 迁移完成后(密文全为当前密钥)重复 sweep 零更新")
    void rotationIdempotentAfterMigration() {
        AesCipher legacy = new AesCipher("legacy-aes-key");
        SecurityDataSweep rotating = new SecurityDataSweep(deviceMapper, serverMapper, upperMapper, aes, legacy);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device(1L, aes.encrypt("12345678"))));
        when(serverMapper.selectList(any())).thenReturn(List.of(server(9L, aes.encrypt("vision-zlm-secret"))));

        rotating.sweep();

        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
    }

    @Test
    @DisplayName("未知密钥密文(新旧均解不开) → 跳过不动数据, 该表其余行仍处理")
    void unknownKeyCiphertextLeftUntouched() {
        AesCipher stranger = new AesCipher("stranger-aes-key");
        SecurityDataSweep rotating = new SecurityDataSweep(deviceMapper, serverMapper, upperMapper, aes,
                new AesCipher("legacy-aes-key"));
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, stranger.encrypt("lost-secret")), device(2L, legacyCipher("12345678"))));
        when(serverMapper.selectList(any())).thenReturn(List.of());

        rotating.sweep();

        // 仅第 2 行(旧密钥可解)被回写, 未知密文行原样未动
        ArgumentCaptor<GbDeviceEntity> dc = ArgumentCaptor.forClass(GbDeviceEntity.class);
        verify(deviceMapper, times(1)).updateById(dc.capture());
        assertEquals(2L, dc.getValue().getId());
        assertEquals("12345678", aes.decrypt(dc.getValue().getPassword()));
    }

    /** 用指定旧密钥加密(独立于 setUp 的当前密钥) */
    private String legacyCipher(String plain) {
        return new AesCipher("legacy-aes-key").encrypt(plain);
    }

    @Test
    @DisplayName("未配置旧密钥(默认态): 当前密钥可解的密文行静默跳过; 解不开的行跳过不动数据")
    void noLegacyConfiguredLeavesCiphertextsAlone() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device(1L, aes.encrypt("12345678")),
                device(2L, new AesCipher("other-key").encrypt("lost-secret"))));
        when(serverMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> task.sweep());

        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        verify(serverMapper, never()).updateById(any(GbMediaServerEntity.class));
    }
}
