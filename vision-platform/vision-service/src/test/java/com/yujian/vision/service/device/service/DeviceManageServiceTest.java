package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.alarm.service.AlarmSubscriptionService;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 设备管理单元测试（M3-B3 阶段1, docs/08 §3）：纯 Mockito, 不起 Spring 上下文/数据库。
 * 锁 create 预建档校验面（编码 20 位/类型 1|2/密码必填/编码唯一）+ 默认值对齐注册自动建档
 * + update 的 name/deviceType patch 语义（null 不改/非法类型拒绝且不落库）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceManageServiceTest {

    /** 合法国标编码：20 位数字（下级平台） */
    private static final String DEVICE_ID = "34020000002000000002";

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private GbDeviceChannelMapper channelMapper;

    @Mock
    private AesCipher aes;

    @Mock
    private CatalogSubscriptionService catalogSubscriptionService;
    @Mock
    private AlarmSubscriptionService alarmSubscriptionService;
    @Mock
    private com.yujian.vision.service.device.rtsp.RtspDeviceManageService rtspDeviceManageService;

    private DeviceManageService service;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeviceManageService(deviceMapper, channelMapper, aes, catalogSubscriptionService,
                alarmSubscriptionService, rtspDeviceManageService);
        // 兜底 stub：非密码路径不触发 encrypt, lenient 避免误报 UnnecessaryStubbing
        lenient().when(aes.encrypt(anyString())).thenReturn("enc:v1:mock-cipher");
    }

    @Test
    @DisplayName("create 合法下级平台建档: 默认值对齐注册自动建档(2016/UDP/心跳60x3/被动收流/离线)")
    void createLowerPlatformOk() {
        when(aes.encrypt("pwd-12345")).thenReturn("enc:v1:TestCipher");
        when(deviceMapper.selectOne(any())).thenReturn(null);

        GbDeviceEntity created = service.create(DEVICE_ID, "区县平台", "pwd-12345",
                2, null, null);

        assertEquals(DEVICE_ID, created.getDeviceId());
        assertEquals("区县平台", created.getName());
        assertEquals(2, created.getDeviceType());
        assertEquals("UDP", created.getTransport());
        assertEquals("2016", created.getProtocolVersion());
        assertEquals(1, created.getMediaTcpMode());
        assertEquals(60, created.getHeartbeatInterval());
        assertEquals(3, created.getHeartbeatTimeout());
        assertEquals(0, created.getOnline());
        // 密码必须走 AES 加密后落库, 不允许存明文
        assertEquals("enc:v1:TestCipher", created.getPassword());
        verify(deviceMapper).insert(created);
    }

    @Test
    @DisplayName("create 编码非 20 位数字(abc) → 400 且不入库")
    void createBadDeviceIdRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.create("abc", "区县平台", "pwd-12345", 2, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(deviceMapper, never()).insert(any(GbDeviceEntity.class));
    }

    @Test
    @DisplayName("create deviceType=3 非法(仅支持1设备/2下级平台) → 400 且不入库")
    void createBadDeviceTypeRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.create(DEVICE_ID, "区县平台", "pwd-12345", 3, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(deviceMapper, never()).insert(any(GbDeviceEntity.class));
    }

    @Test
    @DisplayName("create 密码空白 → 400(生产必须预配置强密码) 且不入库")
    void createBlankPasswordRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.create(DEVICE_ID, "区县平台", "   ", 2, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(deviceMapper, never()).insert(any(GbDeviceEntity.class));
    }

    @Test
    @DisplayName("create 编码重复(已存在档案) → 400 且不入库")
    void createDuplicateDeviceIdRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(new GbDeviceEntity());

        BizException e = assertThrows(BizException.class,
                () -> service.create(DEVICE_ID, "区县平台", "pwd-12345", 2, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(deviceMapper, never()).insert(any(GbDeviceEntity.class));
    }

    @Test
    @DisplayName("update patch: 仅传 name/deviceType → 原地改名改类型并 updateById, 其余字段不动")
    void updateNameAndDeviceTypePatch() {
        GbDeviceEntity existing = new GbDeviceEntity();
        existing.setId(9L);
        existing.setDeviceId(DEVICE_ID);
        existing.setName("旧名");
        existing.setDeviceType(1);
        existing.setTransport("UDP");
        when(deviceMapper.selectOne(any())).thenReturn(existing);

        service.update(DEVICE_ID, "新名", 2, null, null, null, null, null);

        assertEquals("新名", existing.getName());
        assertEquals(2, existing.getDeviceType());
        // patch 语义: 未传字段保持原值
        assertEquals("UDP", existing.getTransport());
        verify(deviceMapper).updateById(existing);
    }

    @Test
    @DisplayName("update deviceType=5 非法值 → 400 且不 updateById")
    void updateBadDeviceTypeRejected() {
        GbDeviceEntity existing = new GbDeviceEntity();
        existing.setId(9L);
        existing.setDeviceId(DEVICE_ID);
        existing.setName("旧名");
        existing.setDeviceType(1);
        when(deviceMapper.selectOne(any())).thenReturn(existing);

        BizException e = assertThrows(BizException.class,
                () -> service.update(DEVICE_ID, "新名", 5, null, null, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
    }

    @Test
    @DisplayName("update 改型(2→1)但名下有未删通道 → 400 禁止修改设备类型, 不落库不清理订阅")
    void updateRejectsTypeChangeWithChannels() {
        GbDeviceEntity existing = new GbDeviceEntity();
        existing.setId(9L);
        existing.setDeviceId(DEVICE_ID);
        existing.setDeviceType(2);
        when(deviceMapper.selectOne(any())).thenReturn(existing);
        when(channelMapper.selectCount(any())).thenReturn(3L);

        BizException e = assertThrows(BizException.class,
                () -> service.update(DEVICE_ID, null, 1, null, null, null, null, null));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertTrue(e.getMessage().contains("禁止修改设备类型"), e.getMessage());
        verify(deviceMapper, never()).updateById(any(GbDeviceEntity.class));
        // 拒绝路径不触达订阅清理
        verify(catalogSubscriptionService, never()).onDeviceOffline(anyString());
    }

    @Test
    @DisplayName("update 改型(2→1)无通道 → 放行落库并防御性清理旧目录订阅")
    void updateTypeChangeWithoutChannelsClearsSubscription() {
        GbDeviceEntity existing = new GbDeviceEntity();
        existing.setId(9L);
        existing.setDeviceId(DEVICE_ID);
        existing.setDeviceType(2);
        when(deviceMapper.selectOne(any())).thenReturn(existing);
        when(channelMapper.selectCount(any())).thenReturn(0L);

        service.update(DEVICE_ID, null, 1, null, null, null, null, null);

        assertEquals(1, existing.getDeviceType());
        // 改型后旧目录订阅作废（防御性清理）
        verify(catalogSubscriptionService).onDeviceOffline(DEVICE_ID);
        verify(deviceMapper).updateById(existing);
    }

    @Test
    @DisplayName("page 批量填充通道数: group 行按 device_db_id 归位, 无通道设备补 0")
    void pageFillsChannelCounts() {
        GbDeviceEntity d1 = new GbDeviceEntity();
        d1.setId(1L);
        d1.setDeviceId(DEVICE_ID);
        GbDeviceEntity d2 = new GbDeviceEntity();
        d2.setId(2L);
        d2.setDeviceId("34020000001320000002");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<GbDeviceEntity> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        p.setRecords(new java.util.ArrayList<>(java.util.List.of(d1, d2)));
        when(deviceMapper.selectPage(any(), any())).thenReturn(p);
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("device_db_id", 2L);
        row.put("cnt", 12L);
        when(channelMapper.selectMaps(any())).thenReturn(java.util.List.of(row));

        var r = service.page(null, null, 1, 10);

        assertEquals(0, r.getRecords().get(0).getChannelCount());
        assertEquals(12, r.getRecords().get(1).getChannelCount());
    }
}
