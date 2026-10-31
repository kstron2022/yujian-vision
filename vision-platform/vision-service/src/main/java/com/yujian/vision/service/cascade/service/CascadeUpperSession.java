package com.yujian.vision.service.cascade.service;

import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import lombok.Data;

/**
 * 级联上级单条注册会话的运行态（内存, docs/08 §2.1）。
 * 持久态（配置/在线状态落库）在 gb_cascade_upper；本对象重启即失, 由 loadAll 重建。
 */
@Data
public class CascadeUpperSession {

    /** 注册应答已发出, 等上级 401/200 */
    public static final String ST_WAIT_CHALLENGE = "WAIT_CHALLENGE";
    /** 注册成功, 心跳保持中 */
    public static final String ST_ONLINE = "ONLINE";
    /** 失败退避中（指数退避重连, docs/08 §5） */
    public static final String ST_BACKOFF = "BACKOFF";

    private final Long upperId;

    /** 最新配置（管理端修改后经 reload 原地替换引用） */
    private volatile GbCascadeUpperEntity upper;

    private volatile String state = ST_BACKOFF;

    /** 本轮注册事务的 CallID/CSeq（401 重试须同 CallID 且 CSeq+1, RFC 3261） */
    private volatile String registerCallId;
    private volatile long registerSn;
    /** 本轮注册是否已带 Authorization 重试过（再次 401 = 密码错误, 不再重试） */
    private volatile boolean authTried;
    private volatile long sentAt;

    /** 下次注册刷新时刻（expires*0.8, 毫秒纪元） */
    private volatile long nextRegisterAt;
    /** 下次心跳发送时刻 */
    private volatile long nextKeepaliveAt;
    /** 最近一次心跳 200 应答时刻（0=尚无） */
    private volatile long lastKeepaliveAckAt;

    /** 退避重连 */
    private volatile long nextRetryAt;
    private volatile int backoffSeconds;

    CascadeUpperSession(Long upperId, GbCascadeUpperEntity upper) {
        this.upperId = upperId;
        this.upper = upper;
    }
}
