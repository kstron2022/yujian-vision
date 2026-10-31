package com.yujian.vision.media.zlm;

/**
 * getSnap 抓帧结果（M3-B7 服务端抓图）：应答 content-type + 原始字节。
 * ZLM 在流未就绪时返回 defaultSnap 占位图（PNG）——是否真图由调用方按
 * content-type 与 JPEG 魔数判定，本类型只承载字节，不做解释。
 */
public record ZlmSnap(String contentType, byte[] body) {
}
