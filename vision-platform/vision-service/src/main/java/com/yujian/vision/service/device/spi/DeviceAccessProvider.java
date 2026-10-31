package com.yujian.vision.service.device.spi;

import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.service.PresetQueryService;

/**
 * 设备接入 SPI（docs/13 §3.2）：协议差异在接入面收敛, GB 实现随 B2 迁入。
 * B1 范围 = 探活 + 目录同步; B2 扩展媒体面分流点。
 */
public interface DeviceAccessProvider {

    DeviceProtocol protocol();

    /**
     * 探活：成功刷新 keepalive_time/在线状态（离线判定仍由 DeviceOfflineTask 按
     * keepalive_time + interval×timeout 统一执行, 协议无关）；失败只记日志不抛。
     */
    void probe(GbDeviceEntity device);

    /** 目录同步：全量比对收敛（新增/更新/软删缺失通道），完成后发 CHANNEL_CHANGE */
    void syncCatalog(GbDeviceEntity device);

    /**
     * 媒体面（M4-B2, docs/13 §5.1）：解析点播拉流源地址。
     * ONVIF: 按 streamProfile 选档(1=主/2=子, 分辨率降序) → GetStreamUri → 归一为
     *        可拉流的 RTSP 地址（嵌账密, 主机按手填 IP 重组）; 失败抛 BizException。
     * GB: 平台是收流方（INVITE 设备推流）, 不经此路径 —— 实现应抛 UnsupportedOperationException。
     *
     * @param streamProfile 码流档 1=主码流 2=子码流
     */
    String resolveStreamUrl(GbDeviceEntity device, GbDeviceChannelEntity channel, int streamProfile);

    /**
     * 媒体面（M4-B2, docs/13 §5）：云台方向/变倍/停止/聚焦。
     * command 为 GB PtzCommand 枚举名（UP/DOWN/LEFT/RIGHT/ZOOM_IN/ZOOM_OUT/FOCUS_NEAR/FOCUS_FAR/STOP）,
     * speed 0-100; 聚焦 2026-09-25 起经 ONVIF Imaging 服务支持; IRIS_* ONVIF 无标准对映
     * （IP 相机自动光圈）, 实现应给明确业务错误。
     */
    void controlPtz(GbDeviceEntity device, GbDeviceChannelEntity channel, String command, int speed);

    /** 媒体面（M4-B2, docs/13 §5）：预置位。action = SET/GOTO/DELETE, presetNum 1-255 */
    void controlPreset(GbDeviceEntity device, GbDeviceChannelEntity channel, String action, int presetNum);

    /**
     * 预置位检索（2026-09-25 现场补齐）：GB 走 SIP PresetQuery（PresetQueryService 内部）,
     * 本 SPI 供 ONVIF GetPresets 等非 SIP 协议实现; 不支持的协议走默认抛错。
     */
    default java.util.List<PresetQueryService.PresetItem> queryPresets(
            GbDeviceEntity device, GbDeviceChannelEntity channel) {
        throw new UnsupportedOperationException("该接入协议不支持预置位检索");
    }

    /**
     * 媒体面（M4-B2, docs/13 §5.2）：快照图字节。
     * ONVIF: GetSnapshotUri 平台代取（Basic 认证兜底）; GB 不经此路径（ZLM 冷抓）。
     */
    byte[] fetchSnapshot(GbDeviceEntity device, GbDeviceChannelEntity channel);

    /**
     * 回放面（M4-B3, docs/13 §5.3）：录像检索（ONVIF Search Service）。
     * ONVIF: FindRecordings → GetRecordingSearchResults, 时间段取 Video track, 平台本地时区;
     * 失败/不支持抛异常, 由调用方走「整窗单段」降级口径。
     * GB 不经此路径（GB 侧走 SIP RecordInfo, 在 RecordQueryService 内）。
     */
    default java.util.List<com.yujian.vision.onvif.model.OnvifRecordingSegment> searchRecordings(
            GbDeviceEntity device, GbDeviceChannelEntity channel,
            java.time.LocalDateTime start, java.time.LocalDateTime end) {
        throw new UnsupportedOperationException("该接入协议不支持 ONVIF Search 检索");
    }

    /**
     * 回放面（M4-B3, docs/13 §5.3）：解析回放拉流源地址（带 starttime/endtime 窗口）。
     * ONVIF: GetReplayUri 优先, 失败降级 GetStreamUri 推导海康 tracks URI（时间窗内嵌 query）,
     * 再经 OnvifUris.normalizePullUrl 归一（嵌账密+主机重组）。GB 不经此路径（INVITE s=Playback）。
     */
    default String resolveReplayUrl(GbDeviceEntity device, GbDeviceChannelEntity channel,
            java.time.LocalDateTime start, java.time.LocalDateTime end) {
        throw new UnsupportedOperationException("该接入协议不支持回放拉流地址解析");
    }
}
