package com.yujian.vision.service.device.onvif;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * ONVIF 合成 20 位国标编码（docs/13 §4）：行政区划+行业取平台域前 10 位 + 类型段 132(网络摄像机)
 * + 7 位序列（Redis 自增）。序列空间从平台域派生, 与厂商分配的真实编码天然错开;
 * 仍逐个查重（设备表+通道表全局活跃行）防偶发撞码, 撞码取下一个。
 */
@Component
@RequiredArgsConstructor
public class OnvifCodeAllocator {

    private static final String SEQ_KEY = "gb:seq:onvif-code";
    /** GB/T 28181 类型段: 132=网络摄像机 */
    private static final String TYPE_SEGMENT = "132";
    private static final int MAX_RETRY = 50;

    private final RedissonClient redisson;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbPlatformProperties platform;

    public String nextDeviceCode() {
        return next();
    }

    public String nextChannelCode() {
        return next();
    }

    private String next() {
        String prefix = codePrefix();
        for (int i = 0; i < MAX_RETRY; i++) {
            long seq = redisson.getAtomicLong(SEQ_KEY).incrementAndGet();
            String code = prefix + String.format("%07d", seq % 10_000_000);
            if (!exists(code)) {
                return code;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "ONVIF 编码分配连续冲突, 请检查平台域配置");
    }

    private String codePrefix() {
        String domain = platform.getDomain();
        String base = domain != null && domain.length() >= 10 ? domain.substring(0, 10) : "3402000000";
        return base + TYPE_SEGMENT;
    }

    private boolean exists(String code) {
        return deviceMapper.selectCount(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, code)
                .isNull(GbDeviceEntity::getDeletedTime)) > 0
                || channelMapper.selectCount(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getChannelId, code)
                        .isNull(GbDeviceChannelEntity::getDeletedTime)) > 0;
    }
}
