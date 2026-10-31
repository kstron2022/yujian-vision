package com.yujian.vision.media.zlm;

/**
 * ZLMediaKit RESTful API 客户端 SPI（docs/04 §3）。
 *
 * <p>实现要点：按 serverId 路由到对应节点、带 secret、连接池化（OkHttp）、
 * 失败重试与超时控制；ZLM 版本迭代 API 参数有差异（stream_mode/ssrc 为较新版本提供），
 * 以实际部署版本 API 文档核对。</p>
 */
public interface ZlmApiClient {

    /**
     * 开启 RTP 收流端口
     *
     * @return ZLM 实际分配的收流端口
     */
    int openRtpServer(String serverId, ZlmOpenRtpServerRequest request);

    /**
     * 关闭 RTP 收流端口（与释放 SSRC 成对调用，docs/06 §2.1 清理三件套）
     */
    void closeRtpServer(String serverId, String streamId);

    /**
     * 强制注销已注册的流（崩溃恢复兜底，docs/06 §8）：closeRtpServer 只关收流端口，
     * 已注册到 ZLM 的流需 close_streams 注销，防孤儿流（AC：平台重启后无孤儿流）。
     * 幂等，流不存在时 ZLM 返回注销数 0。
     */
    void closeStreams(String serverId, String app, String streamId);

    /**
     * 流列表（会话对账任务数据源，docs/06 §7）
     */
    String getMediaList(String serverId);

    /**
     * 流列表（结构化：app/stream/totalReaderCount），对账与节点流数统计用
     */
    java.util.List<ZlmMediaInfo> listMedia(String serverId);

    /**
     * ZLM 版本（/index/api/version, on_server_started 落库展示用）
     */
    String getVersion(String serverId);

    /**
     * 开始录制（docs/09 M2 #3 按计划录制）：/index/api/startRecord, type=1 → MP4。
     * 会话关闭时 ZLM 随流注销自动停止录制并按分片周期落盘（on_record_mp4 逐片上报）,
     * 因此平台侧无需显式 stopRecord。
     *
     * @param type 0:hls 1:mp4
     */
    void startRecord(String serverId, String app, String streamId, int type);

    /**
     * 开始向级联上级推流（M3-B2, docs/08 §2.3）：ZLM 以 UDP 向上级收流地址直发。
     * TCP 场景按对端 a=setup 分流: 对端 passive（作 TCP server）→ {@link #startSendRtpTcp};
     * 对端 active（我方监听）→ {@link #startSendRtpPassive}。
     *
     * @param ssrc 上级 SDP y= 指定的收流 SSRC
     */
    void startSendRtp(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort);

    /**
     * TCP 主动模式向级联上级推流（2026-09-25 LiveGBS 联调补齐）：ZLM 作 TCP client 连上级
     * 被动收流端口（对端 a=setup:passive）——此前唯一主动推流实现硬编码 is_udp=1, TCP 场景
     * 实发 UDP, 对端 TCP 收流恒 none rtp data receive。
     */
    void startSendRtpTcp(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort);

    /**
     * 被动模式向级联上级推流：ZLM 作 TCP server 监听, 上级（a=setup:active）连接取流。
     */
    void startSendRtpPassive(String serverId, String app, String streamId, String ssrc);

    /**
     * 停止向级联上级推流（上级 BYE / 兜底回收）。ssrc 为空时停该流全部 send 任务（ZLM 语义）。
     */
    void stopSendRtp(String serverId, String app, String streamId, String ssrc);

    /**
     * 语音对讲推流（M3-B5）：对音频源流以 ES 裸流模式发 RTP（type=0 kRtpES + only_audio=1 +
     * pt=8 PCMA）到设备 200 OK 应答 SDP 给出的收流地址 —— 设备侧对讲期望裸 G.711A RTP 而非 PS 封装。
     */
    void startSendRtpAudio(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort);

    /**
     * 拉流代理（M3-B6 阶段1 跨节点回源, docs/01 §HA）：目标节点从 sourceUrl 拉流并以
     * (app, stream) 注册本地流。返回 ZLM 代理 key（delStreamProxy 用）。
     * M4 ONVIF 的 GetStreamUri → addStreamProxy 拉流复用本方法。
     */
    String addStreamProxy(String serverId, ZlmAddStreamProxyRequest request);

    /**
     * 删除拉流代理（按 addStreamProxy 返回的 key）。幂等, key 不存在时 ZLM 返回 code!=0 由实现吞掉。
     */
    void delStreamProxy(String serverId, String key);

    /**
     * getSnap 单次 timeout_sec 上限（秒）。ZLM 冷抓真帧实测需 4~5.4s（242 cam1 H265）,
     * 调用方给的单次超时不得低于该量级——<b>提前掐断会让 ZLM 把 snap 播放器留在占位图
     * 卡死态</b>（之后同一 url 全部秒回 PNG, 表现为永久抓帧超时）。
     */
    int SNAP_MAX_TIMEOUT_SECONDS = 15;

    /**
     * 抓帧（M3-B7 服务端抓图, docs/09）：/index/api/getSnap?url=..&timeout_sec=..&expire_sec=..。
     * ZLM 内部播放器拉 url 取一帧, <b>应答是图片字节流</b>而非 JSON。流未就绪时返回
     * defaultSnap 占位图（PNG）——是否真图由调用方按 content-type/JPEG 魔数判定后轮询重试,
     * 本方法只取字节不解释（与 callBinary 的分工: callRoot 强制 JSON code==0, 不能复用）。
     *
     * @param url       待抓流的播放地址（自拉地址, 见 PlayUrlService.buildSelfPullUrl）
     * @param timeoutSec ZLM 侧取帧等待秒数（实现侧收口在 [1, {@link #SNAP_MAX_TIMEOUT_SECONDS}]）
     * @param expireSec  ZLM 侧抓帧结果缓存秒数（同 url 重复调用在缓存期内直接回缓存）
     */
    ZlmSnap getSnap(String serverId, String url, int timeoutSec, int expireSec);

    // TODO(M2): openRtpServerMultiplex（单端口复用评估）
}
