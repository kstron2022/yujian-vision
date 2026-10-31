package com.yujian.vision.sip.transport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SIP 协议要素 ID 生成器（docs/01 §3.3）：
 * CallID 全局唯一、Branch 固定 z9hG4bK 前缀、MESSAGE 查询事务 SN 唯一。
 */
public final class SipIds {

    private static final AtomicLong SN = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L);

    private SipIds() {
    }

    /** Call-ID：UUID（新事务用；对话内后续请求复用原 CallID） */
    public static String newCallId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Via Branch：RFC3261 魔数前缀 z9hG4bK */
    public static String newBranch() {
        return "z9hG4bK" + UUID.randomUUID().toString().replace("-", "");
    }

    /** From/To Tag */
    public static String newTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** MESSAGE 查询事务 SN（与响应按 SN 聚合关联） */
    public static long nextSn() {
        return SN.incrementAndGet();
    }
}
