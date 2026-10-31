package com.yujian.vision.service.media.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体会话行为配置（vision.media）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "vision.media")
public class MediaProperties {

    /**
     * UDP 收流失败自动降级（docs/06 §6）：on_rtp_server_timeout 且会话仍处 INVITING、
     * 收流模式为 UDP 时，BYE 旧对话后改 TCP 被动 re-INVITE。每会话至多降级一次
     * （降级即翻转 tcpMode, 天然防循环）。关闭则维持原行为：直接 FAILED(no_stream)。
     */
    private boolean udpFallbackEnabled = true;

    /**
     * INVITE 已收到最终响应(200 OK)但流一直未到达的兜底关停秒数。
     * 10s 扫描只负责"无最终响应"(docs/06 §6 语义)；已应答的会话交给
     * ZLM on_rtp_server_timeout(默认 15s) → 降级/失败, 此值仅防 hook 失联时的会话泄漏。
     * 必须大于 ZLM timeoutSec + 降级窗口, 否则会掐死降级流程。
     */
    private int answeredInviteMaxSeconds = 60;

    /**
     * ZLM 收流侧 RTP gap 超时秒数（openRtpServer 的 timeoutSec，平台默认 15s，docs/04 §3）。
     * 暂停豁免判死（docs/07 §3, B2 必改 #1）依赖此值: 暂停超过该时长收流侧必已被 ZLM 自关,
     * RESUME/SEEK 无需等死亡 hook 到达即按"收流已死"重开端口续播。部署侧若调整 ZLM timeoutSec 需同步。
     */
    private int receiveTimeoutSec = 15;

    /**
     * /play/sessions 会话列表观测上限（docs/03 §4.3, M3-B4 阶段3 由硬编码 100 参数化——
     * B6 压测挂账: 200 路点播时平台侧计数曾被此值封顶）。仅观测接口防全表回包, 不影响业务;
     * 压测/大并发观测时按需调大。使用侧强制下限 1。
     */
    private int sessionsLimit = 100;

    /**
     * 点播收流节点调度策略（M3-B6 阶段1, docs/09 M3-B6）：first=首个在线节点(历史行为);
     * least-streams=在流数最少(默认, 单点部署与 first 等价); least-bandwidth=实时带宽最小。
     * 未识别值按 least-streams 兜底（MediaNodeScheduler）。
     */
    private String nodeSchedule = "least-streams";

    /**
     * 点播建流失败的换节点重试次数（M3-B6 阶段2 挂账①, docs/06 §2）：openRtpServer 连接拒绝/
     * SSRC 分配失败(ZLM_API_FAILED/SSRC_ALLOC_FAILED)时换下一候选节点重试, 0=关(历史行为)。
     * sendInvite 失败与回放 resume 路径不在重试范围。判死 tick(≤30s)窗口内选中刚死节点的新点播
     * 由此自愈——失败节点的行状态仍由 NodeKeepaliveTask 按保活超时翻转。
     */
    private int inviteNodeRetries = 1;

    /**
     * 按需点播开关（M3-B7, docs/06 §3.1）：ZLM on_stream_not_found 触发——播放器拉的流不存在时,
     * 平台在 ZLM 等待窗口（maxStreamWaitMS）内自动把流建起来。false=只记日志不建流
     * （hook 仍须在 ZLM config.ini 中注册, 未注册则本配置无意义）。
     */
    private boolean onDemandEnabled = true;

    /**
     * 按需点播建流的码流档（M3-B7）：1=主码流(默认) 2=子码流。仅在"该通道无任何活跃实况会话"
     * 需新建时生效——streamId（gb_play_{channelId}）与码流档无关, 已有活跃会话（任意档）一律复用,
     * 故本值只决定新建会话的档位。
     */
    private int onDemandProfile = 1;

    /**
     * RTSP 拉流代理探活的单台超时毫秒（M5-B1, docs/14 §6）：TCP 连接与 OPTIONS 应答各用该值,
     * 探测线程最坏 2×timeout 阻塞。探测在独立 3 线程池执行, 不阻塞公共调度线程。
     */
    private int rtspProbeTimeoutMs = 3000;

    /**
     * RTSP 拉流代理连续探活失败判离线阈值（M5-B1 防抖, docs/14 §6）：周期为设备行
     * heartbeat_interval(默认 60s), 连续 N 次失败才判离线, 成功即清零恢复——防源侧秒级
     * 抖动把上级目录 Status 打成 OFF（上级拒点/置灰）。设为 1 即退化为单次失败判离线。
     */
    private int rtspProbeFailThreshold = 3;
}
