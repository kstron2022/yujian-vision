package com.yujian.vision.sip.transport;

import lombok.Builder;
import lombok.Data;

/**
 * SIP 消息发送上下文：屏蔽具体栈的事务/对话 API，业务侧只关心信令要素。
 * CallID/CSeq/Branch/SN 由 vision-sip 统一生成（docs/01 §3.3），不入业务代码。
 */
@Data
@Builder
public class SipMessageContext {

    /** 目标栈（多栈分片路由键） */
    private String stackId;

    /** 请求方法：REGISTER/MESSAGE/INVITE/ACK/BYE/INFO/CANCEL */
    private String method;

    /** Request-URI，如 sip:{目标编码}@{目标域}:{port} */
    private String requestUri;

    /** From 头（本平台国标编码@域） */
    private String from;

    /** To 头（目标国标编码@域） */
    private String to;

    /** 关联的 CallID（响应/后续请求复用同一对话；新事务留空由栈生成） */
    private String callId;

    /** From 头 tag（对话内后续请求复用 INVITE 的 tag；留空由栈生成） */
    private String fromTag;

    /** To 头 tag（对话内请求填对端 200 OK 回复的 tag；新事务留空） */
    private String toTag;

    /** MESSAGE 查询事务的 SN（与响应聚合关联；INVITE/BYE 场景即 CSeq 序号） */
    private Long sn;

    /** 消息体：MANSCDP+xml / SDP / MANSRTSP */
    private String body;

    /** 消息体 Content-Type */
    private String contentType;

    /**
     * 消息体字符集（2026-09-25 级联 LiveGBS 乱码修复）：非空时 Content-Type 头追加
     * charset 参数, jain-sip 序列化按它字节化（缺省回退 UTF-8 静态默认）。
     * 调用方须保证 body 内 XML 声明的 encoding 与此一致（见 CascadeOutboundCodec）。
     */
    private String contentCharset;

    /**
     * 是否对话内后续请求（RFC 3261 §12.2）：true 时经 Dialog 发送, From/To tag、CSeq、Contact、
     * Route 均由 Dialog 统一维护（与对话内 BYE 同机制）, sn 字段不参与 CSeq；false 时按新事务发送。
     */
    private boolean inDialog;

    /** 目标地址（UDP 场景必填） */
    private String targetHost;

    private Integer targetPort;

    /** Authorization 头值（级联 UAC 对上级 401 挑战的 Digest 应答, docs/08 §2.1；含 "Digest" 前缀, 留空不带该头） */
    private String authorization;

    /** Expires 头（级联 UAC REGISTER 的注册有效期秒数, 0=注销；留空不带该头） */
    private Integer expires;

    /** Event 头（SUBSCRIBE 的事件包名, 如 Catalog, RFC 3265；留空不带该头, M3-B3 阶段4 目录订阅） */
    private String event;

    /**
     * Subscription-State 头（NOTIFY 必带, RFC 3265; 原值含参数如 "active;expires=1234",
     * 留空不带该头, M3-B4 阶段2 上级订阅制报警上报）。
     */
    private String subscriptionState;
}
