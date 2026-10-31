package com.yujian.vision.service.device.onvif;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.onvif.model.OnvifDiscoveredDevice;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.onvif.OnvifDiscoveryService.DiscoveredView;
import com.yujian.vision.service.ha.HaRuntimeService;
import java.util.List;
import java.util.Set;
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
 * WS-Discovery 探测服务单测（M4-B4）: HA 门禁 + asViews 映射/已接入标记。
 * OnvifDiscovery.probe() 有真 socket, 单测不触达——组播面由 242 sim_discovery.py 桩端到端覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnvifDiscoveryServiceTest {

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private HaRuntimeService ha;

    private OnvifDiscoveryService service;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbDeviceEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new OnvifDiscoveryService(deviceMapper, ha);
        when(ha.isStandby()).thenReturn(false);
        when(deviceMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("备实例探测 → 拒绝并提示")
    void standbyRejected() {
        when(ha.isStandby()).thenReturn(true);
        BizException e = assertThrows(BizException.class, () -> service.probe());
        assertTrue(e.getMessage().contains("备实例"), e.getMessage());
    }

    @Test
    @DisplayName("asViews: registered 按 ip:port 匹配(含 cam1 自定义 50080 端口形态)")
    void asViewsRegisteredMatching() {
        GbDeviceEntity cam1 = new GbDeviceEntity();
        cam1.setIp("10.0.0.201");
        cam1.setPort(50080);
        when(deviceMapper.selectList(any())).thenReturn(List.of(cam1));

        Set<String> registered = Set.of("10.0.0.201:50080");
        List<DiscoveredView> views = OnvifDiscoveryService.asViews(
                List.of(new OnvifDiscoveredDevice("u1", "10.0.0.201", 50080, "http://x", "cam1"),
                        new OnvifDiscoveredDevice("u2", "10.0.0.99", 8899, "http://y", "")),
                registered);
        assertTrue(views.get(0).registered());
        assertFalse(views.get(1).registered());
    }

    @Test
    @DisplayName("asViews: 端口不一致=未接入")
    void asViewsPortMismatch() {
        List<DiscoveredView> views = OnvifDiscoveryService.asViews(
                List.of(new OnvifDiscoveredDevice("u3", "10.0.0.201", 80, "http://z", "")),
                Set.of("10.0.0.201:50080"));
        org.junit.jupiter.api.Assertions.assertFalse(views.get(0).registered());
    }
}
