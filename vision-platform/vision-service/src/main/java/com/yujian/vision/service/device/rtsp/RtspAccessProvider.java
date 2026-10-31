package com.yujian.vision.service.device.rtsp;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.media.rtsp.RtspProbeClient;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.service.DeviceOfflineService;
import com.yujian.vision.service.device.spi.DeviceAccessProvider;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.media.config.MediaProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RTSP 拉流代理接入面（M5-B1, docs/14 §5/§6）: DeviceAccessProvider SPI 的 RTSP 实现。
 * resolveStreamUrl = 档案 source_url 密文解密（无设备外呼, 与 ONVIF GetStreamUri 同分流点）;
 * probe = RTSP OPTIONS 轻量探测（TCP+交换即断, 不拉流; 任何 RTSP/ 应答含 401 均判源可达）,
 * 防抖=连续 N 次失败(vision.media.rtsp-probe-fail-threshold, 默认 3)才判离线, 成功即清零恢复;
 * PTZ/回放/快照代理路径明确拒绝或走 ZLM 通用链路（能力位与前端入口配合置灰）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RtspAccessProvider implements DeviceAccessProvider {

    private final AesCipher aes;
    private final DeviceOfflineService offlineService;
    private final MediaProperties media;

    /** 防抖计数（deviceDbId → 连续失败次数; 进程内存态, 实例重启清零多探一轮无碍） */
    private final Map<Long, Integer> failCounts = new ConcurrentHashMap<>();

    @Override
    public DeviceProtocol protocol() {
        return DeviceProtocol.RTSP;
    }

    @Override
    public void probe(GbDeviceEntity device) {
        String url = decryptUrl(device);
        RtspProbeClient client = new RtspProbeClient(media.getRtspProbeTimeoutMs(),
                media.getRtspProbeTimeoutMs());
        RtspProbeClient.ProbeResult r = client.probe(url);
        if (r.ok()) {
            failCounts.remove(device.getId());
            offlineService.keepaliveAndOnline(device);
            return;
        }
        int fails = failCounts.merge(device.getId(), 1, Integer::sum);
        int threshold = Math.max(1, media.getRtspProbeFailThreshold());
        if (fails >= threshold) {
            if (device.getOnline() != null && device.getOnline() == 1) {
                offlineService.markOffline(device, "RTSP 探活连续 " + fails + " 次失败: " + r.message());
            }
            failCounts.remove(device.getId());
        }
    }

    @Override
    public void syncCatalog(GbDeviceEntity device) {
        // 单通道在建档时已建（RtspDeviceManageService.doCreate）, 无目录语义
    }

    @Override
    public String resolveStreamUrl(GbDeviceEntity device, GbDeviceChannelEntity channel, int streamProfile) {
        return decryptUrl(device);
    }

    @Override
    public void controlPtz(GbDeviceEntity device, GbDeviceChannelEntity channel, String command, int speed) {
        throw new BizException(ErrorCode.BAD_REQUEST, "拉流代理不支持云台控制");
    }

    @Override
    public void controlPreset(GbDeviceEntity device, GbDeviceChannelEntity channel, String action, int presetNum) {
        throw new BizException(ErrorCode.BAD_REQUEST, "拉流代理不支持预置位");
    }

    @Override
    public byte[] fetchSnapshot(GbDeviceEntity device, GbDeviceChannelEntity channel) {
        // RTSP 与 GB 同口径走 ZLM 冷抓（SnapshotService getSnap）, 不经 provider 代取
        throw new BizException(ErrorCode.BAD_REQUEST, "拉流代理快照走流媒体冷抓, 不经接入面");
    }

    /** 档案拉流地址解密（密文缺失/解密失败按业务错误, 不触发拉流） */
    private String decryptUrl(GbDeviceEntity device) {
        if (device.getSourceUrl() == null || device.getSourceUrl().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "拉流代理设备缺少源地址: " + device.getDeviceId());
        }
        try {
            return aes.decrypt(device.getSourceUrl());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "拉流地址解密失败(密钥轮换或密文损坏): " + device.getDeviceId());
        }
    }
}
