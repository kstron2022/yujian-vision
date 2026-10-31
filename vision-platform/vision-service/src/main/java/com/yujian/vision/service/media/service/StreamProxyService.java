package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmAddStreamProxyRequest;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.springframework.stereotype.Service;

/**
 * 跨节点拉流代理（M3-B6 阶段1 跨节点回源, docs/01 §HA）：
 * 观看可经任一在线节点分发——目标节点 addStreamProxy 从源节点拉签名 TS, 观众拉目标节点地址。
 * 源流读者+1（代理在 = 有观看, none_reader 自动关流语义不破坏; 随源会话关闭拆除）。
 * 索引在 Redis（gb:proxy:idx:{streamId} → map{serverId → proxyKey}）, 会话清理逐节点 best-effort 拆除。
 */
@Slf4j
@Service
public class StreamProxyService {

    /** Redis 索引前缀: gb:proxy:idx:{streamId} → map{serverId → proxyKey} */
    static final String IDX_PREFIX = "gb:proxy:idx:";

    /**
     * 常驻代理索引前缀（M5-B1, docs/14 §7）: gb:proxy:always:{streamId} → map{serverId → proxyKey}。
     * 与按需索引分离的动机: 会话清理（cleanupForStream）豁免常驻代理——观众走光会话关闭时
     * 常驻流不断; 代理生命周期由收敛循环（RtspAlwaysOnTask）管理而非会话。
     */
    static final String ALWAYS_IDX_PREFIX = "gb:proxy:always:";

    private final GbMediaSessionMapper sessionMapper;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final PlayUrlService urlService;
    private final RedissonClient redisson;

    public StreamProxyService(GbMediaSessionMapper sessionMapper, GbMediaServerMapper serverMapper,
            ZlmApiClient zlm, PlayUrlService urlService, RedissonClient redisson) {
        this.sessionMapper = sessionMapper;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.urlService = urlService;
        this.redisson = redisson;
    }

    /**
     * 跨节点观看: 在 targetServerId 上确保存在 streamId 的拉流代理, 返回目标节点播放地址。
     * 目标节点 = 源节点时直接返回源节点地址（不建代理）。
     * 会话须 STREAMING（源流存在）; 源/目标节点行缺失或目标不在线抛业务异常。
     * 代理已存在（索引命中）时幂等跳过; Redis 丢失后 ZLM 已有同名代理视为成功（key 未知,
     * 清理走 closeStreams 兜底）。
     */
    public Map<String, Object> ensureProxy(String streamId, String targetServerId) {
        GbMediaSessionEntity s = sessionMapper.selectOne(new LambdaQueryWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getStreamId, streamId)
                .eq(GbMediaSessionEntity::getStatus, "STREAMING")
                .last("LIMIT 1"));
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "流不存在或未在播: " + streamId);
        }
        if (targetServerId == null || targetServerId.isBlank()
                || s.getServerId().equals(targetServerId)) {
            // 未指定节点 / 指定即源节点: 直接返回源节点地址（不建代理）
            return resultOf(streamId, s.getServerId(), s.getServerId(), false, 0);
        }
        GbMediaServerEntity src = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, s.getServerId()).last("LIMIT 1"));
        GbMediaServerEntity dst = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, targetServerId).last("LIMIT 1"));
        if (src == null || dst == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "节点不存在: src="
                    + s.getServerId() + " dst=" + targetServerId);
        }
        if (dst.getStatus() == null || dst.getStatus() != 1) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "目标节点不在线: " + targetServerId);
        }
        // 幂等: 索引命中跳过重建
        RMap<String, String> idx = redisson.getMap(IDX_PREFIX + streamId);
        String existed = idx.get(targetServerId);
        if (existed != null) {
            log.info("跨节点观看: 代理已存在, 幂等返回: stream={} node={}", streamId, targetServerId);
            return resultOf(streamId, s.getServerId(), targetServerId, false, 0);
        }
        String url = urlService.buildInternalPullUrl(src, streamId, 3600);
        String key;
        try {
            key = zlm.addStreamProxy(targetServerId, ZlmAddStreamProxyRequest.forDispatch("rtp", streamId, url));
        } catch (BizException e) {
            // Redis 丢失后 ZLM 已有同名代理: 流已在目标节点 → 视为成功, key 未知记 "?"（清理走 closeStreams 兜底）
            if (streamExistsOn(targetServerId, streamId)) {
                log.warn("跨节点观看: addStreamProxy 失败但目标节点已有同名流, 视为成功: stream={} node={}",
                        streamId, targetServerId);
                idx.put(targetServerId, "?");
                return resultOf(streamId, s.getServerId(), targetServerId, true, 0);
            }
            throw e;
        }
        idx.put(targetServerId, key);
        log.info("跨节点观看: 代理已建立: stream={} src={} dst={} key={}",
                streamId, s.getServerId(), targetServerId, key);
        return resultOf(streamId, s.getServerId(), targetServerId, true, 0);
    }

    /** 登记源节点代理键（M4-B2 ONVIF: 源流本身即拉流代理, onvifInviteNew 建流成功后登记）。
     * 与跨节点分发代理共用同一索引/清理链路: 会话清理（SessionCleanupService 步骤 2c）
     * cleanupForStream 会一并 delStreamProxy 拆除源代理, 生命周期与会话同生共死。
     */
    public void registerSource(String streamId, String serverId, String key) {
        redisson.<String, String>getMap(IDX_PREFIX + streamId).put(serverId, key);
    }

    /**
     * 登记常驻代理（M5-B1）: 收敛循环拉起 always_on 设备的源流后登记常驻索引。
     * 该索引不参与会话清理链路（cleanupForStream 拆除时跳过常驻条目）,
     * 仅由收敛循环/设备编辑（关闭常驻）/设备删除维护。
     */
    public void registerAlwaysOn(String streamId, String serverId, String key) {
        redisson.<String, String>getMap(ALWAYS_IDX_PREFIX + streamId).put(serverId, key);
    }

    /** 常驻索引条目（streamId → map{serverId → proxyKey}）; 无登记返回空 map */
    public Map<String, String> alwaysOnEntries(String streamId) {
        Map<String, String> entries = redisson.<String, String>getMap(ALWAYS_IDX_PREFIX + streamId)
                .readAllMap();
        return entries == null ? Map.of() : entries;
    }

    /** 移除常驻索引单节点条目（收敛循环发现 key 失效时清理残留; 返回移除前的 key） */
    public String removeAlwaysOn(String streamId, String serverId) {
        return redisson.<String, String>getMap(ALWAYS_IDX_PREFIX + streamId).remove(serverId);
    }

    /** 清空常驻索引（设备删除/关闭常驻时; 不拆 ZLM 代理, 调用方先显式拆除） */
    public void clearAlwaysOn(String streamId) {
        redisson.getMap(ALWAYS_IDX_PREFIX + streamId).delete();
    }

    /**
     * 全量常驻 streamId 集合（M5-B1, 对账保护用）: RKeys 扫常驻索引前缀并剥前缀。
     * SessionReconcileTask 反向对账（ZLM 有流但平台无活跃会话 → 强制注销）必须并入本集合——
     * 常驻流无观众时平台侧无会话行, 不豁免会被 60s 对账强关 → 收敛循环重拉 → 死循环
     * （评审 P0-2, docs/14 §5）。
     */
    public java.util.Set<String> alwaysOnStreamIds() {
        // getKeysByPattern 系列在 Redisson 3.45 已全部 @Deprecated, 改用 KeysScanOptions 形式
        // （同为 SCAN 游标遍历, limit 不设=全量; chunkSize 仅影响每次 SCAN 往返的键数）
        Iterable<String> keys = redisson.getKeys().getKeys(KeysScanOptions.defaults()
                .pattern(ALWAYS_IDX_PREFIX + "*").chunkSize(1000));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String k : keys) {
            ids.add(k.substring(ALWAYS_IDX_PREFIX.length()));
        }
        return ids;
    }

    /** 按 serverId 集合强制注销同名流（closeStreams 兜底口径, 常驻拆除用） */
    public void closeStreamsOn(String serverId, String streamId) {
        try {
            zlm.closeStreams(serverId, "rtp", streamId);
        } catch (Exception e) {
            log.warn("closeStreams 失败(节点可能离线, 由节点侧自愈): stream={} node={}",
                    streamId, serverId, e);
        }
    }

    /**
     * 会话清理联动（SessionCleanupService 步骤 2c）: 源流已关, 拆除各分发节点上的代理。
     * 逐节点 best-effort（单节点失败不影响其余）; key 未知("?")或拆除失败用 closeStreams 兜底。
     * M5-B1 豁免: 常驻索引登记的 {serverId → key} 跳过拆除——观众走光会话关闭时常驻流不断;
     * 豁免判定按节点(serverId): 常驻同节点上的一切条目(含极窄竞态下 key 相同的分发代理)都不拆,
     * 常驻条目本身不在此索引中本就不会被这里拆除。
     */
    public void cleanupForStream(String streamId) {
        RMap<String, String> idx = redisson.getMap(IDX_PREFIX + streamId);
        Map<String, String> entries = idx.readAllMap();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, String> always = alwaysOnEntries(streamId);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String serverId = e.getKey();
            String key = e.getValue();
            if (always.containsKey(serverId)) {
                log.info("常驻代理豁免会话清理: stream={} node={}", streamId, serverId);
                continue;
            }
            try {
                if (key == null || "?".equals(key)) {
                    zlm.closeStreams(serverId, "rtp", streamId);
                } else {
                    zlm.delStreamProxy(serverId, key);
                }
            } catch (Exception ex) {
                log.warn("代理拆除失败(closeStreams 兜底): stream={} node={}", streamId, serverId, ex);
                try {
                    zlm.closeStreams(serverId, "rtp", streamId);
                } catch (Exception ex2) {
                    log.warn("代理兜底拆除也失败(节点可能离线, 由节点侧自愈): stream={} node={}",
                            streamId, serverId, ex2);
                }
            }
        }
        idx.delete();
        log.info("跨节点代理已清理: stream={} nodes={}", streamId, entries.keySet());
    }

    /** 节点上流是否活跃（M5-B1 常驻复用判定用; 查询异常按不活跃处理） */
    public boolean streamActiveOn(String serverId, String streamId) {
        return streamExistsOn(serverId, streamId);
    }

    /** 目标节点上是否已有该流（addStreamProxy 冲突判定） */
    private boolean streamExistsOn(String serverId, String streamId) {
        try {
            List<ZlmMediaInfo> medias = zlm.listMedia(serverId);
            return medias != null && medias.stream().anyMatch(
                    m -> "rtp".equals(m.app()) && streamId.equals(m.stream()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 统一应答形状（docs/03 契约: streamId/sourceNode/node/proxied/urls） */
    private Map<String, Object> resultOf(String streamId, String sourceNode, String node,
            boolean proxied, int unused) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("streamId", streamId);
        r.put("sourceNode", sourceNode);
        r.put("node", node);
        r.put("proxied", proxied);
        r.put("urls", urlService.buildUrls(node, streamId, 3600));
        return r;
    }
}
