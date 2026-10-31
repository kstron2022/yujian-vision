package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmNodeInfo;
import com.yujian.vision.media.zlm.ZlmNodeRegistry;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import org.springframework.stereotype.Service;

/**
 * ZlmNodeRegistry 的 DB 实现（gb_media_server 表）。
 */
@Service
public class DbZlmNodeRegistry implements ZlmNodeRegistry {

    private final GbMediaServerMapper mapper;
    private final AesCipher aes;

    public DbZlmNodeRegistry(GbMediaServerMapper mapper, AesCipher aes) {
        this.mapper = mapper;
        this.aes = aes;
    }

    @Override
    public ZlmNodeInfo resolve(String serverId) {
        GbMediaServerEntity node = mapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, serverId)
                .last("LIMIT 1"));
        if (node == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "未知流媒体节点: " + serverId);
        }
        // ZLM API 要求明文 secret, 库内为密文统一在此解密（docs/02 §0）
        return new ZlmNodeInfo(node.getServerId(), node.getHostIp(), node.getHttpPort(),
                aes.decrypt(node.getSignSecret()));
    }
}
