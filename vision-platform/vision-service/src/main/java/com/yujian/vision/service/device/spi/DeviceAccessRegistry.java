package com.yujian.vision.service.device.spi;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 接入协议 → 实现注册表（docs/13 §3.2 分流点）：Spring 收集全部 DeviceAccessProvider,
 * 调用方按设备 accessProtocol 取实现; 协议未交付实现时给明确业务错误。
 */
@Component
public class DeviceAccessRegistry {

    private final Map<DeviceProtocol, DeviceAccessProvider> providers = new EnumMap<>(DeviceProtocol.class);

    public DeviceAccessRegistry(List<DeviceAccessProvider> list) {
        for (DeviceAccessProvider p : list) {
            providers.put(p.protocol(), p);
        }
    }

    public DeviceAccessProvider of(DeviceProtocol protocol) {
        DeviceAccessProvider p = providers.get(protocol);
        if (p == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "接入协议暂未支持该操作: " + protocol);
        }
        return p;
    }
}
