package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ZLM 流媒体节点管理（docs/03 §7, M1-9 A4 契约缺口补齐）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerManageService {

    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlmClient;
    private final ObjectMapper objectMapper;
    private final AesCipher aes;

    public List<GbMediaServerEntity> list() {
        List<GbMediaServerEntity> nodes = serverMapper.selectList(new LambdaQueryWrapper<GbMediaServerEntity>()
                .orderByAsc(GbMediaServerEntity::getId));
        // 流数实时化: stream_count 库值由对账任务 60s 刷一次(快照滞后), 管理页流数列与
        // 查流抽屉(实时 getMediaList)口径不一致会被当作数据 bug——在线节点实时查 ZLM 覆盖
        // 响应值(仅响应, 落库快照仍归对账); 单节点查询失败保留库值不阻断列表。
        for (GbMediaServerEntity node : nodes) {
            if (node.getStatus() == null || node.getStatus() != 1) {
                continue;
            }
            try {
                node.setStreamCount(liveRtpCount(node.getServerId()));
            } catch (Exception e) {
                log.debug("实时流数查询失败, 保留对账快照: server={} err={}",
                        node.getServerId(), e.getMessage());
            }
        }
        return nodes;
    }

    /** ZLM 当前 app=rtp 去重流数（与查流抽屉/对账落库同口径） */
    private int liveRtpCount(String serverId) {
        List<com.yujian.vision.media.zlm.ZlmMediaInfo> medias = zlmClient.listMedia(serverId);
        if (medias == null) {
            return 0;
        }
        return (int) medias.stream()
                .filter(m -> "rtp".equals(m.app()))
                .map(com.yujian.vision.media.zlm.ZlmMediaInfo::stream)
                .distinct()
                .count();
    }

    public GbMediaServerEntity get(String serverId) {
        GbMediaServerEntity s = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, serverId));
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "节点不存在: " + serverId);
        }
        return s;
    }

    public void create(GbMediaServerEntity server) {
        if (!StringUtils.hasText(server.getServerId()) || !StringUtils.hasText(server.getHostIp())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serverId 与 hostIp 不能为空");
        }
        if (serverMapper.selectCount(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, server.getServerId())) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "serverId 已存在");
        }
        if (server.getStatus() == null) {
            server.setStatus(0);
        }
        if (StringUtils.hasText(server.getSignSecret())) {
            server.setSignSecret(aes.encrypt(server.getSignSecret()));
        }
        server.setId(null);
        serverMapper.insert(server);
    }

    public void update(String serverId, GbMediaServerEntity patch) {
        GbMediaServerEntity s = get(serverId);
        if (StringUtils.hasText(patch.getHostIp())) {
            s.setHostIp(patch.getHostIp());
        }
        if (StringUtils.hasText(patch.getPublicIp())) {
            s.setPublicIp(patch.getPublicIp());
        }
        if (patch.getHttpPort() != null) {
            s.setHttpPort(patch.getHttpPort());
        }
        if (patch.getRtpPortStart() != null) {
            s.setRtpPortStart(patch.getRtpPortStart());
        }
        if (patch.getRtpPortEnd() != null) {
            s.setRtpPortEnd(patch.getRtpPortEnd());
        }
        if (StringUtils.hasText(patch.getSignSecret())) {
            // docs/02 §0: sign_secret 落库前加密（读点统一 decrypt）
            s.setSignSecret(aes.encrypt(patch.getSignSecret()));
        }
        serverMapper.updateById(s);
    }

    public void delete(String serverId) {
        GbMediaServerEntity s = get(serverId);
        serverMapper.deleteById(s.getId());
    }

    /** 节点流列表（ZLM getMediaList 原样透传, 解析为 JSON 对象） */
    public Map<String, Object> streams(String serverId) {
        get(serverId);
        try {
            String raw = zlmClient.getMediaList(serverId);
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("获取节点流列表失败: server={}", serverId, e);
            throw new BizException(ErrorCode.ZLM_API_FAILED, "获取流列表失败");
        }
    }
}
