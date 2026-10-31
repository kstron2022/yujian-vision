package com.yujian.vision.sip.transport;

/**
 * SIP 传输抽象层 SPI（docs/01 §5）。
 *
 * <p>当前实现：RestComm 维护分支 jain-sip-ri；预留替换为 Netty 自研栈的演进路径。
 * 业务模块（vision-service / vision-protocol-gb28181）只依赖本接口，不直接依赖具体栈。</p>
 *
 * <p>实现约束（docs/01 §5 SIP 并发模型）：栈回调内不做阻塞 IO，统一投递业务线程池；
 * 收到的信令需携带栈标识与来源地址（received/rport），供 NAT 场景回发。</p>
 */
public interface SipTransportProvider {

    /**
     * sendResponse headers 里的预留键: 显式指定 2xx 应答的 To tag（订阅对话 tag 由业务层生成并持有,
     * 对话内 NOTIFY 的 From tag 须与它一致; 传输层不再随机生成, M3-B4 阶段2）。
     */
    String TO_TAG_HEADER_KEY = "ToTag";

    /**
     * 注册入站消息监听器（业务层在启动时设置一次）
     */
    void setListener(SipEventListener listener);

    /**
     * 启动一个 SIP 栈实例（幂等：同 stackId 重复启动忽略）
     */
    void start(SipStackDescriptor descriptor);

    /**
     * 停止指定栈
     */
    void stop(String stackId);

    /**
     * 栈是否存活
     */
    boolean isRunning(String stackId);

    /**
     * 当前运行中的栈数量（多栈分片监控指标）
     */
    int activeStacks();

    /**
     * 发送 SIP 消息（新事务或既有对话内消息）
     */
    void send(SipMessageContext message);

    /**
     * 对入站请求应答（401 挑战 / 200 OK 等）
     *
     * @param inReplyTo 收到的请求事件（含栈层事务引用）
     * @param statusCode 应答状态码（401/200/603...）
     * @param headers 附加头（如 WWW-Authenticate / Expires / Date），键为头名、值为头值
     */
    void sendResponse(SipEvent inReplyTo, int statusCode, java.util.Map<String, String> headers);

    /**
     * 对入站请求应答并携带消息体（级联 INVITE 200 OK 的 SDP 应答, docs/08 §2.3）。
     * body 为 null/空时等价三参版本。
     */
    default void sendResponse(SipEvent inReplyTo, int statusCode, java.util.Map<String, String> headers,
            String body, String contentType) {
        sendResponse(inReplyTo, statusCode, headers);
    }

    /**
     * 取消本平台发出的 INVITE 事务（INVITE 超时, docs/06 §6）。无对应事务时静默忽略。
     */
    void cancel(String stackId, String callId);

    /**
     * 按传输协议解析外呼栈 id（设备注册时记录的 transport → 同协议栈）。
     * 未启用该协议栈时返回 null, 调用方应回退平台默认栈。
     *
     * @param transport UDP / TCP（大小写不敏感）
     */
    String stackIdForTransport(String transport);
}
