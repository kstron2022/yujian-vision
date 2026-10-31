package com.yujian.vision.service.device.onvif;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.onvif.OnvifDiscovery;
import com.yujian.vision.onvif.model.OnvifDiscoveredDevice;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.ha.HaRuntimeService;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * WS-Discovery 同网段探测服务（M4-B4, docs/13 §2.2）。
 * 发现 ≠ 接入: 只回 ip:port/型号线索/已接入标记, 建档仍走手填+账密（前端点「填入」后手输账密）。
 * 已接入判定 = ip:port 与活跃 ONVIF 档案匹配（设备 UUID 不落库, 无从比对; 同 B1 同地址唯一口径）。
 * HA 门禁: 仅主实例探测（docs/13 §2.2 与对账任务同模式）, 备实例返回明确提示。
 */
@Slf4j
@Service
public class OnvifDiscoveryService {

    /** 视图（前端列表行; modelHint 仅供人眼识别, 已接入行不可填入） */
    public record DiscoveredView(String ip, int port, String serviceUrl, String modelHint, boolean registered) {
    }

    private final GbDeviceMapper deviceMapper;
    private final HaRuntimeService ha;

    /** 组播出接口 IP（多网卡环境必配, 如 242=10.0.0.242; 空=内核按路由表选口） */
    @Value("${vision.onvif.discovery.interface-ip:}")
    private String interfaceIp;

    /** 收响应窗口（毫秒）: 短了漏设备, 长了接口等待久; 4s 实测够同网段设备应答 */
    @Value("${vision.onvif.discovery.timeout-ms:4000}")
    private long timeoutMs;

    public OnvifDiscoveryService(GbDeviceMapper deviceMapper, HaRuntimeService ha) {
        this.deviceMapper = deviceMapper;
        this.ha = ha;
    }

    public List<DiscoveredView> probe() {
        if (ha.isStandby()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前为备实例, 请在主实例执行探测");
        }
        List<OnvifDiscoveredDevice> found;
        try {
            found = new OnvifDiscovery(interfaceIp, Duration.ofMillis(timeoutMs)).probe();
        } catch (IOException e) {
            log.warn("WS-Discovery 探测失败: interface={} err={}", StringUtils.hasText(interfaceIp) ? interfaceIp : "auto", e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "探测执行失败: " + e.getMessage());
        }
        Set<String> registered = activeOnvifEndpoints();
        return asViews(found, registered);
    }

    /** 视图映射 + 已接入匹配（包可见: 单测直测映射口径, socket 面由 242 桩覆盖） */
    static List<DiscoveredView> asViews(List<OnvifDiscoveredDevice> found, Set<String> registered) {
        return found.stream()
                .map(d -> new DiscoveredView(d.ip(), d.port(), d.serviceUrl(), d.modelHint(),
                        registered.contains(d.ip() + ":" + d.port())))
                .toList();
    }

    /** 活跃 ONVIF 档案的 ip:port 集合（软删不计; 端口含自定义口如 cam1 的 50080） */
    private Set<String> activeOnvifEndpoints() {
        Set<String> out = new HashSet<>();
        for (GbDeviceEntity d : deviceMapper.selectList(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.ONVIF)
                .isNull(GbDeviceEntity::getDeletedTime))) {
            out.add(d.getIp() + ":" + d.getPort());
        }
        return out;
    }
}
