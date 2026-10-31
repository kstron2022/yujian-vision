package com.yujian.vision.service.device.service;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 目录刷新/进度按协议分流（docs/13 §3.2, M4-B1）：REST 入口唯一, GB 走既有 CatalogSyncService,
 * ONVIF 走 DeviceAccessProvider; RTSP 无目录语义（单通道建档自动生成, SIP Catalog 不适用）直接拒
 * （前端已隐藏入口, 这里兜底——曾会落入 GB 同步发 SIP MESSAGE 石沉大海, 前端永卡"同步中"）。
 */
@Service
@RequiredArgsConstructor
public class DeviceCatalogFacade {

    private final DeviceAccessRegistry registry;
    private final CatalogSyncService gbCatalogSync;

    public void refresh(GbDeviceEntity device) {
        if (DeviceProtocol.of(device) == DeviceProtocol.ONVIF) {
            registry.of(DeviceProtocol.ONVIF).syncCatalog(device);
            return;
        }
        if (DeviceProtocol.of(device) == DeviceProtocol.RTSP) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流代理设备无目录可刷新");
        }
        gbCatalogSync.sync(device.getDeviceId());
    }

    public CatalogSyncService.SyncProgress progress(GbDeviceEntity device) {
        if (DeviceProtocol.of(device) == DeviceProtocol.ONVIF) {
            // ONVIF 目录同步为同步调用（HTTP 请求内完成）, 无异步进度会话
            return new CatalogSyncService.SyncProgress(false, 0, 0);
        }
        return gbCatalogSync.progress(device.getDeviceId());
    }
}
