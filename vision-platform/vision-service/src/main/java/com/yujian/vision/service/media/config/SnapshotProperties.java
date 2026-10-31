package com.yujian.vision.service.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 服务端抓图行为配置（vision.snapshot, M3-B7 阶段2, docs/09）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.snapshot")
public class SnapshotProperties {

    /** 总开关: 关=不自动抓、手动抓图接口不可用 */
    private boolean enabled = true;

    /**
     * 空闲通道抓图开关（有带宽成本的能力）: 允许对当前无流通道临时建流抓图, 抓完立即停流。
     * 停流走 stop() 的 viewer_count 语义——抓图者作为唯一观看者退出（1→0）触发清理三件套,
     * 期间有真人点播则 viewer 保持 ≥1, 流不误停。默认 false。
     */
    private boolean idleEnabled = false;

    /** 点播流就绪自动抓一张（只受 enabled 约束, 与 idle-enabled 无关） */
    private boolean autoOnPlay = true;

    /**
     * 自动抓图刷新间隔秒: 已有图未超该秒数则自动抓跳过; 0=仅无图时抓。
     */
    private int autoRefreshSeconds = 1800;

    /** 图片落盘目录（prod /opt/vision/data/snapshot; dev ./data/snapshot, 仓库已 ignore） */
    private String dir = "./data/snapshot";

    /** 抓帧总预算毫秒（含占位图轮询）。15s 依据 242 真机实测: H265 冷抓第一帧需 4~5.4s;
     *  调小到 5s 以下会把 ZLM snap 播放器打成占位图卡死态, 表现为永久 1013 */
    private long timeoutMs = 15000;

    /** 占位图轮询间隔毫秒 */
    private long pollIntervalMs = 500;

    /** 点播自动抓图延迟秒: 等 ZLM 首帧稳定, 防 on_stream_changed 一到就抓到黑帧 */
    private int autoDelaySeconds = 3;

    /** 缩略图宽 160（列表列显示「截图」列显示用, 另存小图; 否则 20 行 × 原图约 3MB/页） */
    private int thumbWidth = 160;

    /** 抓图图片签名 URL TTL 秒（泄露仅暴露一张通道截图, docs/09 风险条目） */
    private long urlTtlSeconds = 300;

    /** 空闲抓图临时建流的码流档: 1=主码流(默认) 2=子码流（子码流省带宽） */
    private int idleProfile = 1;

    /** 抓图执行并发 */
    private int concurrency = 4;
}
