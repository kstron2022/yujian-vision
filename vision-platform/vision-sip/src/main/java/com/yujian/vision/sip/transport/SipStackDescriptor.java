package com.yujian.vision.sip.transport;

/**
 * SIP 栈描述符：一个栈实例监听一个地址/端口（多栈分片，按设备编码哈希分散到 N 个栈规避单栈锁竞争）
 *
 * @param stackId   栈标识（分片键）
 * @param host      监听地址
 * @param port      监听端口（如 5060）
 * @param transport UDP / TCP
 */
public record SipStackDescriptor(String stackId, String host, int port, String transport) {
}
