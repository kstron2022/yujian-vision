package com.yujian.vision.media.zlm;

/**
 * ZLM 节点连接信息（来自 gb_media_server, service 侧 DB 注册表实现）
 */
public record ZlmNodeInfo(String serverId, String hostIp, int httpPort, String secret) {
}
