package com.yujian.vision.gb28181.spi;

/**
 * 协议能力项：用于按设备协商适配器与流程分支（docs/01 §3.2）
 */
public enum ProtocolCapability {

    /** TCP 被动收流（默认） */
    TCP_PASSIVE,

    /** UDP 收流兜底 */
    UDP_FALLBACK,

    /** 目录订阅（2022） */
    CATALOG_SUBSCRIBE,

    /** 录像检索 RecordInfo */
    RECORD_QUERY,

    /** 回放控制 MANSRTSP（INFO） */
    MANSRTSP_CONTROL,

    /** 语音对讲 */
    TALK
}
