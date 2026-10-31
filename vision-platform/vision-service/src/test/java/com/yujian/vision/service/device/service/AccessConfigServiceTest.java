package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.device.entity.GbAccessConfigEntity;
import com.yujian.vision.service.device.mapper.GbAccessConfigMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.config.SipStackProperties;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 国标接入配置单元测试（M3-B3 阶段1, docs/08 §3.1）：纯 Mockito。
 * 锁：视图不泄漏密码明文（只出 defaultPasswordSet/source）、密码 ≥8 位校验、
 * 落库 insert/update 分支、未建档设备默认密码解析（库内自定义 > 内置默认）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessConfigServiceTest {

    @Mock
    private GbAccessConfigMapper mapper;

    @Mock
    private AesCipher aes;

    private GbPlatformProperties platform;
    private SipStackProperties sipStacks;
    private AccessConfigService service;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbAccessConfigEntity.class);
    }

    @BeforeEach
    void setUp() {
        platform = new GbPlatformProperties();
        sipStacks = new SipStackProperties();
        SipStackProperties.StackSpec main = new SipStackProperties.StackSpec();
        main.setStackId("main");
        main.setPort(5060);
        main.setTransport("UDP");
        sipStacks.setStacks(List.of(main));
        service = new AccessConfigService(mapper, platform, sipStacks, aes);
    }

    @Test
    @DisplayName("get: 未自定义密码时 defaultPasswordSet=false 且 source=config, 不出明文")
    void viewWithoutCustomPassword() {
        when(mapper.selectById(1L)).thenReturn(null);
        AccessConfigService.AccessConfigView view = service.get();
        assertEquals("34020000002000000001", view.serverId());
        assertEquals("3402000000", view.domain());
        assertEquals(5060, view.sipPort());
        assertEquals("UDP", view.transports());
        assertFalse(view.defaultPasswordSet());
        assertEquals("config", view.defaultPasswordSource());
    }

    @Test
    @DisplayName("get: 库内已自定义密码时 defaultPasswordSet=true 且 source=db")
    void viewWithCustomPassword() {
        GbAccessConfigEntity row = new GbAccessConfigEntity();
        row.setId(1L);
        row.setDefaultPassword("AES(cipher)");
        when(mapper.selectById(1L)).thenReturn(row);
        AccessConfigService.AccessConfigView view = service.get();
        assertTrue(view.defaultPasswordSet());
        assertEquals("db", view.defaultPasswordSource());
    }

    @Test
    @DisplayName("update: 密码少于 8 位拒绝且不落库")
    void updateRejectsShortPassword() {
        assertThrows(BizException.class, () -> service.update("1234567", null));
        assertThrows(BizException.class, () -> service.update("   ", null));
        verify(mapper, never()).insert(any(GbAccessConfigEntity.class));
        verify(mapper, never()).updateById(any(GbAccessConfigEntity.class));
    }

    @Test
    @DisplayName("update: 无行时 insert(id=1) 且存 AES 密文")
    void updateInsertsWhenAbsent() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(aes.encrypt("strong-pass-8")).thenReturn("AES(x)");
        service.update("strong-pass-8", null);
        ArgumentCaptor<GbAccessConfigEntity> captor = ArgumentCaptor.forClass(GbAccessConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getId());
        assertEquals("AES(x)", captor.getValue().getDefaultPassword());
        verify(mapper, never()).updateById(any(GbAccessConfigEntity.class));
    }

    @Test
    @DisplayName("update: 已有行时 update 保留单行结构")
    void updateUpdatesExistingRow() {
        GbAccessConfigEntity row = new GbAccessConfigEntity();
        row.setId(1L);
        row.setDefaultPassword("AES(old)");
        when(mapper.selectById(1L)).thenReturn(row);
        when(aes.encrypt("another-pass-8")).thenReturn("AES(y)");
        service.update("another-pass-8", null);
        verify(mapper).updateById(row);
        assertEquals("AES(y)", row.getDefaultPassword());
        verify(mapper, never()).insert(any(GbAccessConfigEntity.class));
    }

    @Test
    @DisplayName("update: allowAutoRegister patch, 0/1 合法且与密码互不影响")
    void updateAllowAutoRegisterPatch() {
        when(mapper.selectById(1L)).thenReturn(null);
        service.update(null, 1);
        ArgumentCaptor<GbAccessConfigEntity> captor = ArgumentCaptor.forClass(GbAccessConfigEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getAllowAutoRegister());
        assertEquals(null, captor.getValue().getDefaultPassword());
        verify(mapper, never()).updateById(any(GbAccessConfigEntity.class));
        // 已有行 patch 开关不动密码
        GbAccessConfigEntity row = new GbAccessConfigEntity();
        row.setId(1L);
        row.setAllowAutoRegister(1);
        when(mapper.selectById(1L)).thenReturn(row);
        service.update(null, 0);
        assertEquals(0, row.getAllowAutoRegister());
        assertEquals(null, row.getDefaultPassword());
        verify(mapper).updateById(row);
    }

    @Test
    @DisplayName("isAutoRegisterAllowed: 无配置行=默认白名单, allow=1 才开放")
    void whitelistByDefault() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertFalse(service.isAutoRegisterAllowed());
        GbAccessConfigEntity row = new GbAccessConfigEntity();
        row.setId(1L);
        row.setAllowAutoRegister(1);
        when(mapper.selectById(1L)).thenReturn(row);
        assertTrue(service.isAutoRegisterAllowed());
    }

    @Test
    @DisplayName("update: allowAutoRegister 非法值拒绝")
    void updateRejectsBadAllowFlag() {
        BizException e = assertThrows(BizException.class, () -> service.update(null, 5));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("resolveDefaultPassword: 库内自定义优先, 否则回退内置默认")
    void resolvePrefersDbThenBuiltin() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertEquals("12345678", service.resolveDefaultPassword());
        GbAccessConfigEntity row = new GbAccessConfigEntity();
        row.setId(1L);
        row.setDefaultPassword("AES(cipher)");
        when(mapper.selectById(1L)).thenReturn(row);
        when(aes.decrypt("AES(cipher)")).thenReturn("custom-pass-8");
        assertEquals("custom-pass-8", service.resolveDefaultPassword());
    }
}
