package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.monitor.NodeMetricsTask;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 点播收流节点调度（M3-B6 阶段1, docs/01 §HA / docs/09 M3-B6）。
 *
 * <p>策略 {@code vision.media.node-schedule}：
 * first=按 id 取首个在线节点（B6 前的历史行为）；least-streams=在流数最少（默认,
 * 单点部署与 first 等价）；least-bandwidth=实时带宽最小（B2 带宽监控为输入）。</p>
 *
 * <p>数据源 = NodeMetricsTask 30s 快照（streams/bandwidthBytes, 内存态）：
 * 平台刚启动快照为空时所有候选按 0 计, tie-break 回落 id 升序 = first 行为, 无需特判；
 * ZLM 不可达的节点（streams=null）排最后, 仅当全部不可达时才按 id 兜底。
 * 快照为进程内数据——SIP 多实例分片是 B6 挂账, 当前单实例前提成立。</p>
 */
@Slf4j
@Service
public class MediaNodeScheduler {

    /** 快照缺省值：streams/bandwidth 未知按 0 参与排序（新增节点未采集 ≈ 空闲） */
    private static final long UNKNOWN_ZERO = 0L;
    /** 不可达节点排序键：排最后, 全部不可达时各键相等回落 id */
    private static final long UNREACHABLE = Long.MAX_VALUE;

    private final GbMediaServerMapper serverMapper;
    private final MediaProperties props;
    /** 惰性解析: NodeMetricsTask 与本类存在跨包环（经 CascadeInviteService 回 MediaSessionService）,
     *  构造期直依赖会成环, select() 时才取快照（语义不变: 快照本就是请求时数据） */
    private final org.springframework.beans.factory.ObjectProvider<NodeMetricsTask> metricsTask;

    public MediaNodeScheduler(GbMediaServerMapper serverMapper,
            org.springframework.beans.factory.ObjectProvider<NodeMetricsTask> metricsTask,
            MediaProperties props) {
        this.serverMapper = serverMapper;
        this.metricsTask = metricsTask;
        this.props = props;
    }

    /**
     * 按策略选一个在线节点。无在线节点抛 ZLM_API_FAILED（与 inviteNew 原语义一致）。
     */
    public GbMediaServerEntity select() {
        return select(java.util.Set.of());
    }

    /**
     * 按策略从候选中选一个在线节点（M3-B6 阶段2 挂账①）: excludeServerIds 为本次点播
     * 已失败节点（openRtpServer/SSRC 分配失败）, 排除后为空抛 ZLM_API_FAILED 并列出被剔除节点。
     * 排除先于单节点短路——唯一节点被排除同样抛异常而非返回它。
     */
    public GbMediaServerEntity select(java.util.Set<String> excludeServerIds) {
        return pick(candidates(excludeServerIds));
    }

    /**
     * 指定优先节点 + 排除集（M3-B7 按需点播）: 优先节点在候选内时直接选它——on_stream_not_found
     * 触发时播放器的挂起请求就在该节点上, 流建到别的节点等于没建（请求节点上没有这个流）。
     * 优先节点不在候选（不在线/已被排除）时回落常规策略并告警: 节点行状态由保活翻转, 可能滞后于
     * 实际, 回落保证功能不因单节点状态异常整体失效（播放器重试时由新一轮调度自愈）。
     */
    public GbMediaServerEntity select(String preferredServerId, java.util.Set<String> excludeServerIds) {
        List<GbMediaServerEntity> online = candidates(excludeServerIds);
        if (preferredServerId != null && !preferredServerId.isBlank()) {
            for (GbMediaServerEntity n : online) {
                if (preferredServerId.equals(n.getServerId())) {
                    log.info("节点调度: preferred={} 命中, 候选={}", preferredServerId, serverIds(online));
                    return n;
                }
            }
            log.warn("节点调度: preferred={} 不在候选(不在线或已剔除), 回落策略选择, 候选={}",
                    preferredServerId, serverIds(online));
        }
        return pick(online);
    }

    /** 在线候选节点（按 id 升序, 已剔除 excludeServerIds）; 空则抛异常（提示语区分"无在线节点"与"全被剔除"） */
    private List<GbMediaServerEntity> candidates(java.util.Set<String> excludeServerIds) {
        List<GbMediaServerEntity> online = serverMapper.selectList(
                new LambdaQueryWrapper<GbMediaServerEntity>()
                        .eq(GbMediaServerEntity::getStatus, 1)
                        .orderByAsc(GbMediaServerEntity::getId));
        if (excludeServerIds != null && !excludeServerIds.isEmpty()) {
            online = online.stream()
                    .filter(n -> !excludeServerIds.contains(n.getServerId()))
                    .toList();
        }
        if (online == null || online.isEmpty()) {
            throw new BizException(ErrorCode.ZLM_API_FAILED,
                    (excludeServerIds == null || excludeServerIds.isEmpty())
                            ? "无在线流媒体节点"
                            : "无可用流媒体节点(已剔除失败节点: " + String.join(",", excludeServerIds) + ")");
        }
        return online;
    }

    /** 候选内按策略挑一个（单候选短路, 不查指标快照） */
    private GbMediaServerEntity pick(List<GbMediaServerEntity> online) {
        if (online.size() == 1) {
            return online.get(0);
        }
        String strategy = props.getNodeSchedule();
        NodeMetricsTask task = metricsTask.getIfAvailable();
        Map<String, Object> snap = task == null ? Map.of() : task.snapshot();
        NodeLoad load = nodeLoadOf(snap);
        GbMediaServerEntity picked = switch (strategy == null ? "" : strategy) {
            case "first" -> online.get(0);
            case "least-bandwidth" -> online.stream().min(
                    Comparator.<GbMediaServerEntity>comparingLong(n -> bwKey(load, n.getServerId()))
                            .thenComparingLong(n -> streamsKey(load, n.getServerId()))
                            .thenComparing(n -> n.getId() == null ? Long.MAX_VALUE : n.getId())).orElse(online.get(0));
            // 默认 least-streams（含未识别值兜底, 配置错误不致命）
            default -> online.stream().min(
                    Comparator.<GbMediaServerEntity>comparingLong(n -> streamsKey(load, n.getServerId()))
                            .thenComparingLong(n -> bwKey(load, n.getServerId()))
                            .thenComparing(n -> n.getId() == null ? Long.MAX_VALUE : n.getId())).orElse(online.get(0));
        };
        log.info("节点调度: strategy={} picked={} candidates={}", strategy,
                picked.getServerId(), serverIds(online));
        return picked;
    }

    private static List<String> serverIds(List<GbMediaServerEntity> nodes) {
        return nodes.stream().map(GbMediaServerEntity::getServerId).toList();
    }

    /** 解析快照 nodes 段为 server → (streams, bandwidthBytes)（缺节点 = 未知按 0） */
    private static NodeLoad nodeLoadOf(Map<String, Object> snap) {
        NodeLoad load = new NodeLoad();
        if (snap == null) {
            return load;
        }
        Object nodesObj = snap.get("nodes");
        if (!(nodesObj instanceof List<?> list)) {
            return load;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || m.get("server") == null) {
                continue;
            }
            String server = String.valueOf(m.get("server"));
            Object streams = m.get("streams");
            Object bw = m.get("bandwidthBytes");
            load.streams.put(server, streams instanceof Number n ? n.longValue() : null);
            load.bandwidth.put(server, bw instanceof Number n ? n.longValue() : null);
        }
        return load;
    }

    /** streams 排序键：null(ZLM 不可达)排最后 */
    private static long streamsKey(NodeLoad load, String server) {
        Long v = load.streams.get(server);
        return v == null ? UNREACHABLE : v;
    }

    /** bandwidth 排序键：null(不可达/未采集)按 0（streams 键已把不可达排最后, 此处不重复惩罚） */
    private static long bwKey(NodeLoad load, String server) {
        Long v = load.bandwidth.get(server);
        return v == null ? UNKNOWN_ZERO : v;
    }

    /** 快照解析结果（进程内一次性解析, 避免排序比较器里反复拆箱） */
    private static final class NodeLoad {
        final Map<String, Long> streams = new java.util.HashMap<>();
        final Map<String, Long> bandwidth = new java.util.HashMap<>();
    }
}
