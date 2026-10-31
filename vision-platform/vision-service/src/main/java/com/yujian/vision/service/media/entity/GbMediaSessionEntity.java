package com.yujian.vision.service.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_media_session 媒体会话实体（docs/02 §2.4）。
 * active_flag 为数据库生成列（活跃会话非空, 配合 uk_active 防并发双开流）, 不映射由 DB 维护。
 */
@Data
@TableName("gb_media_session")
public class GbMediaSessionEntity {

    /** 会话类型: 实况（session_type 字典, V1） */
    public static final int TYPE_LIVE = 1;
    /** 会话类型: 回放（session_type 字典, V1/V11） */
    public static final int TYPE_PLAYBACK = 2;
    /** 会话类型: 语音对讲（M3-B5, V22）; streamId=设备腿(openRtpServer, SDP offer 端口), audioStreamId=音频源腿(app=talk) */
    public static final int TYPE_TALK = 4;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 1:实况 2:回放 3:下载 4:对讲 5:级联转发(M3) */
    private Integer sessionType;

    /** ZLM streamId（实况 gb_play_{channelId} 固定值, 天然支持复用, docs/06 §3; 回放 gb_playback_{channelId}_{HHmmss}_{rand} 每次唯一, docs/07 §2.2; 对讲 gb_talk_{channelId}_{rand}=设备腿收流端口绑定, M3-B5） */
    private String streamId;

    private String app;

    /** 对讲音频源流ID（预留列, V22; 当前实现为平台直推——WS 收 G.711A 后平台 RTP 直发, 不经 ZLM） */
    private String audioStreamId;

    /** 对讲音频源收流端口（预留列, V22; 同上） */
    private Integer audioPort;

    private Long deviceDbId;

    private Long channelDbId;

    /** 国标编码（冗余便于查询） */
    private String deviceId;

    private String channelId;

    /** 承载节点 */
    private String serverId;

    private String ssrc;

    /** 1:TCP被动 0:UDP */
    private Integer tcpMode;

    /** 码流 1:主码流 2:子码流 */
    private Integer streamProfile;

    /** ZLM 分配的收流端口 */
    private Integer rtpPort;

    private String callId;

    /** 发起用户（审计） */
    private Long userId;

    private Integer viewerCount;

    /** INIT/INVITING/STREAMING/CLOSING/CLOSED/FAILED/CANCELLED */
    private String status;

    /** user_stop/none_reader/device_offline/stream_lost/invite_timeout/invite_rejected/no_stream/platform_restart */
    private String stopReason;

    private LocalDateTime inviteTime;

    /** 回放窗口起（仅 sessionType=2, V11） */
    private LocalDateTime playbackStart;

    /** 回放窗口止（仅 sessionType=2, V11） */
    private LocalDateTime playbackEnd;

    /** 流就绪时间 */
    private LocalDateTime startTime;

    private LocalDateTime stopTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
