package com.yujian.vision.sip.transport;

import lombok.Builder;
import lombok.Data;

/**
 * 入站 SIP 事件（栈回调的统一封装，屏蔽 jain-sip 类型）
 */
@Data
@Builder
public class SipEvent {

    public enum Type { REQUEST, RESPONSE }

    /** 收到该消息的栈 */
    private String stackId;

    private Type type;

    /** REGISTER / MESSAGE / INVITE / BYE / INFO / ACK ... */
    private String method;

    /** 响应状态码（RESPONSE 时有值：200/401/487/504...） */
    private Integer statusCode;

    /** 响应携带的 CSeq 序号（RESPONSE 时有值：回显所应答请求的 CSeq, ACK 回显用, RFC 3261 §13.2.2.4） */
    private Long cseq;

    private String callId;

    /** From 头的用户部分（对端国标编码；响应方向为平台侧编码） */
    private String fromUser;

    /** From 头 tag（入站 SUBSCRIBE 对话识别: 对话内 NOTIFY 的 To tag 即它, M3-B4 阶段2） */
    private String fromTag;

    private String fromHost;

    /** To 头的用户部分 */
    private String toUser;

    /** To 头 tag（INVITE 200 OK 后构造同对话 BYE/ACK 用） */
    private String toTag;

    /** Request-URI 的用户部分（GB/T 28181 上级 INVITE 的目标通道编码在此, 与 To 头可能不一致） */
    private String requestUser;

    private String contentType;

    /** 消息体：MANSCDP+xml / SDP / MANSRTSP */
    private String body;

    /** 来源地址（NAT 后真实来源，回发优先用） */
    private String sourceIp;

    private Integer sourcePort;

    private String transport;

    /** REGISTER 的 Authorization 头原文（Digest 校验用） */
    private String authorization;

    /** 响应的 WWW-Authenticate 头原文（401 挑战, 级联 UAC 注册用, docs/08 §2.1） */
    private String wwwAuthenticate;

    /** REGISTER 的 Expires 头（0=注销） */
    private Integer expires;

    /** Event 头（入站 SUBSCRIBE 的事件包名, 如 Alarm; 留空=未带, M3-B4 阶段2） */
    private String event;

    /** Contact 头拆解：设备真实联系地址（后续主动发信令的目标） */
    private String contactUser;
    private String contactHost;
    private Integer contactPort;

    /** 栈层 ServerTransaction 引用（transport 应答回公用，业务层勿碰） */
    private transient Object serverTransaction;

    /** 栈层原始 Request 引用（无事务时的无状态应答回公用，业务层勿碰） */
    private transient Object request;
}
