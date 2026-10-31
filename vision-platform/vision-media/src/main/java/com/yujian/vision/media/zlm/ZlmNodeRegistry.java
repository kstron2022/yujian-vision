package com.yujian.vision.media.zlm;

/**
 * ZLM 节点注册表（serverId → 连接信息），DB 实现在 vision-service（docs/04 §3 按节点路由）
 */
public interface ZlmNodeRegistry {

    /** 按节点标识解析连接信息；未知节点抛 BizException(ZLM_API_FAILED) */
    ZlmNodeInfo resolve(String serverId);
}
