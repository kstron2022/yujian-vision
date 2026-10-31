package com.yujian.vision.service.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.service.cascade.service.CascadeInviteService;
import com.yujian.vision.service.cascade.service.CascadeUpperManager;
import com.yujian.vision.service.cascade.service.CascadeUpperSession;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.service.SsrcPoolService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 节点/会话/设备指标采集任务（docs/09 M2 #8）：30s 一轮（fixedDelay）, 只读 + gauge 打点。
 *
 * <p><b>指标全集口径表（docs 回写锚点, 与代码同步维护）</b></p>
 * <pre>
 * 指标名                        | 类型    | labels  | 口径与来源
 * -----------------------------+---------+---------+--------------------------------------------
 * vision.session.active         | gauge   | state   | 活跃态会话数(INIT/INVITING/STREAMING/CLOSING,
 *                                               | 一次 selectList 后 Java 分组, 未出现状态置 0); 来源 gb_media_session
 * vision.cascade.relay.active   | gauge   | -       | 在途级联转推路数(CascadeInviteService.sending 内存态,
 *                                               | 重启清零上级重 INVITE 自愈); 转推流量已含在
 *                                               | vision.node.bandwidth.bytes 内不重复计
 * vision.cascade.uppers.online  | gauge   | -       | 运行态 ONLINE 上级数(内存会话, 退避中不计); 来源 CascadeUpperManager
 * vision.device.online          | gauge   | -       | online=1 且 deleted_time IS NULL 设备数; 来源 gb_device
 * vision.device.total           | gauge   | -       | deleted_time IS NULL 设备数; 来源 gb_device
 * vision.node.status            | gauge   | server  | 节点在线状态(null 按 0); 来源 gb_media_server
 * vision.node.zlm.reachable     | gauge   | server  | 本轮 ZLM getMediaList 可达性(1/0), 仅 status=1 节点刷新;
 *                                               | 异常时置 0 且跳过该节点流指标(gauge 保留上一轮值); 来源 ZLM API
 * vision.node.streams           | gauge   | server  | app=rtp 的去重流数; 来源 ZLM getMediaList
 * vision.node.viewers           | gauge   | server  | sum(每流 viewers); 每流取该流条目中首个非 null totalReaderCount;
 *                                               | 来源 ZLM getMediaList
 * vision.node.bandwidth.bytes   | gauge   | server  | sum(每流带宽); 每流取该流各 schema 条目 bytesSpeed 的 MAX
 *                                               | (同流多 schema 条目, 取 max 防跨 schema 重复计数);
 *                                               | 多读者场景口径实测后回写 docs/04; 来源 ZLM getMediaList
 * vision.node.stream.capacity   | gauge   | server  | rtpPortEnd-rtpPortStart+1, 端口段无效不打点; 来源 gb_media_server
 * vision.node.ssrc.allocated    | gauge   | server  | 节点 SSRC 占用数; 来源 Redis gb:ssrc:inuse:{serverId}
 * vision.hook                   | counter | event   | ZLM hook 事件数(HookService 入口打点)
 * vision.invite                 | counter | result  | 点播/回放 INVITE 计数(sent/answered/failed/fallback)
 * vision.login                  | counter | result  | 登录计数(success/fail)
 * </pre>
 *
 * <p>多实例说明：本任务只读 + 进程内 gauge 打点, <b>不抢 Redisson 分布式锁</b>——gauge 挂在
 * 本进程 Micrometer 注册表上, 多实例各自打点在 Prometheus 侧按 instance 维度区分, 互不干扰
 * 也无写副作用（与 SessionReconcileTask/NodeKeepaliveTask 这类"有写副作用必须单实例"的任务不同）。
 * 单轮整体 try/catch, 异常仅记日志并保留上一轮 gauge 值, 不影响调度线程。</p>
 */
@Slf4j
@Component
public class NodeMetricsTask {

    /** 会话活跃态全集（与 DB 生成列 active_flag 口径一致, docs/06 §2） */
    private static final Set<String> SESSION_STATES = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");
    /** 国标收流 app 固定 rtp（docs/04 §4） */
    private static final String RTP_APP = "rtp";
    /** 节点维度 gauge 指标名全集（残留序列治理用, 与口径表保持同步） */
    private static final List<String> NODE_GAUGE_METRICS = List.of(
            "vision.node.status", "vision.node.zlm.reachable", "vision.node.streams",
            "vision.node.viewers", "vision.node.bandwidth.bytes", "vision.node.stream.capacity",
            "vision.node.ssrc.allocated");
    /** snapshot 时间戳格式（/api/metrics/overview 消费侧直接展示用） */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GbMediaSessionMapper sessionMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbMediaServerMapper serverMapper;
    private final ZlmApiClient zlm;
    private final SsrcPoolService ssrcPool;
    private final VisionMetrics metrics;
    private final CascadeInviteService cascadeInvites;
    private final CascadeUpperManager cascadeUppers;

    /** 最近一轮采集快照（volatile: 采集线程写, Web 线程读, 无锁可见） */
    private volatile Map<String, Object> latest = Map.of();

    public NodeMetricsTask(GbMediaSessionMapper sessionMapper, GbDeviceMapper deviceMapper,
            GbMediaServerMapper serverMapper, ZlmApiClient zlm, SsrcPoolService ssrcPool,
            VisionMetrics metrics, CascadeInviteService cascadeInvites, CascadeUpperManager cascadeUppers) {
        this.sessionMapper = sessionMapper;
        this.deviceMapper = deviceMapper;
        this.serverMapper = serverMapper;
        this.zlm = zlm;
        this.ssrcPool = ssrcPool;
        this.metrics = metrics;
        this.cascadeInvites = cascadeInvites;
        this.cascadeUppers = cascadeUppers;
    }

    /** 30s 一轮（fixedDelay: 上一轮结束后再计时, 慢轮次不会堆积重叠） */
    @Scheduled(fixedDelay = 30_000)
    public void collect() {
        try {
            doCollect();
        } catch (Exception e) {
            // 单轮失败只保留上一轮 gauge 值, 绝不让调度线程带异常退出
            log.warn("监控指标采集异常(保留上一轮值): {}", e.getMessage(), e);
        }
    }

    /** 包私有便于单测：一轮采集 = 会话 gauge + 设备 gauge + 节点 gauge, 末尾刷新 snapshot */
    void doCollect() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("collectTime", LocalDateTime.now().format(FMT));
        snap.put("sessions", collectSessions());
        snap.put("devices", collectDevices());
        snap.put("nodes", collectNodes());
        snap.put("cascade", collectCascade());
        this.latest = snap;
    }

    /** 会话活跃态计数 + gauge（未出现的活跃状态也打 0, Grafana 上不缺序列）。
     *  B5: 计数下推数据库 group by, 替换 B4 的"全量拉实体仅计数"（B4 挂账清偿） */
    private Map<String, Long> collectSessions() {
        Map<String, Long> counts = new TreeMap<>();
        for (String state : SESSION_STATES) {
            counts.put(state, 0L);
        }
        List<Map<String, Object>> rows = sessionMapper.selectMaps(
                new QueryWrapper<GbMediaSessionEntity>()
                        .select("status", "count(*) as cnt")
                        .in("status", SESSION_STATES)
                        .groupBy("status"));
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status == null || cnt == null) {
                continue;
            }
            String state = String.valueOf(status);
            if (SESSION_STATES.contains(state)) {
                counts.put(state, Long.parseLong(String.valueOf(cnt)));
            }
        }
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            metrics.gauge("vision.session.active", e.getValue(), "state", e.getKey());
        }
        return counts;
    }

    /** 级联 gauge（B2 阶段 3）：在途转推路数 + 运行态 ONLINE 上级数（内存运行态, 退避中不计）。
     *  转推带宽已含在 vision.node.bandwidth.bytes（ZLM 视角全流口径）, 不另设带宽指标防重复计。 */
    private Map<String, Object> collectCascade() {
        int relayActive = cascadeInvites.sendingCount();
        int uppersOnline = 0;
        Map<Long, Map<String, Object>> uppers = cascadeUppers.snapshot();
        if (uppers != null) {
            for (Map<String, Object> s : uppers.values()) {
                if (CascadeUpperSession.ST_ONLINE.equals(s.get("state"))) {
                    uppersOnline++;
                }
            }
        }
        metrics.gauge("vision.cascade.relay.active", relayActive);
        metrics.gauge("vision.cascade.uppers.online", uppersOnline);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relayActive", relayActive);
        data.put("uppersOnline", uppersOnline);
        return data;
    }

    /** 设备在线/总数 gauge（软删口径统一 deleted_time IS NULL, docs/02 第 0 节） */
    private Map<String, Long> collectDevices() {
        Long online = deviceMapper.selectCount(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getOnline, 1)
                .isNull(GbDeviceEntity::getDeletedTime));
        Long total = deviceMapper.selectCount(new LambdaQueryWrapper<GbDeviceEntity>()
                .isNull(GbDeviceEntity::getDeletedTime));
        long onlineN = online == null ? 0L : online;
        long totalN = total == null ? 0L : total;
        metrics.gauge("vision.device.online", onlineN);
        metrics.gauge("vision.device.total", totalN);
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("online", onlineN);
        data.put("total", totalN);
        return data;
    }

    /** 节点循环：每节点一组 server=label 的 gauge + snapshot 条目 */
    private List<Map<String, Object>> collectNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<GbMediaServerEntity> rows = serverMapper.selectList(null);
        if (rows == null) {
            return nodes;
        }
        for (GbMediaServerEntity node : rows) {
            if (node == null || node.getServerId() == null || node.getServerId().isBlank()) {
                continue;
            }
            nodes.add(collectNode(node));
        }
        cleanupStaleGauges(rows);
        return nodes;
    }

    /**
     * 节点残留序列治理（评审 B4-#1）：节点从 gb_media_server 删除后, gauge 池中的
     * vision.node.*{server=旧id} 若不摘除, Prometheus 侧永久残留且停在最后值——幽灵节点
     * 虚高 sum(vision_node_status)、容量面板出现不存在的主机。每轮末尾比对 DB 节点集摘除；
     * 多实例下重复 remove 幂等无害。整体 try/catch：治理失败只影响面板整洁度, 不影响采集。
     */
    private void cleanupStaleGauges(List<GbMediaServerEntity> rows) {
        try {
            Set<String> current = new java.util.HashSet<>();
            for (GbMediaServerEntity node : rows) {
                if (node != null && node.getServerId() != null && !node.getServerId().isBlank()) {
                    current.add(node.getServerId());
                }
            }
            for (String metric : NODE_GAUGE_METRICS) {
                for (String server : metrics.tagValues(metric, "server")) {
                    if (!current.contains(server)) {
                        metrics.remove(metric, "server", server);
                        log.info("监控治理: 清理已删除节点的残留序列: {} server={}", metric, server);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("监控治理失败(下一轮重试): {}", e.getMessage());
        }
    }

    /**
     * 单节点打点：status=1 才拉 ZLM 流表（离线节点连 getMediaList 都不白调, 与 NodeKeepaliveTask
     * 同口径）；流指标异常跳过保留上一轮值。
     */
    private Map<String, Object> collectNode(GbMediaServerEntity node) {
        String server = node.getServerId();
        int status = node.getStatus() == null ? 0 : node.getStatus();
        int capacity = portCapacity(node);
        metrics.gauge("vision.node.status", status, "server", server);

        boolean reachable = false;
        Integer streams = null;
        Integer viewers = null;
        Long bandwidth = null;
        Integer ssrcAllocated = null;
        if (status == 1) {
            try {
                RtpAggregation agg = aggregateRtp(zlm.listMedia(server));
                reachable = true;
                streams = agg.streams();
                viewers = agg.viewers();
                bandwidth = agg.bandwidthBytes();
                metrics.gauge("vision.node.streams", streams, "server", server);
                metrics.gauge("vision.node.viewers", viewers, "server", server);
                metrics.gauge("vision.node.bandwidth.bytes", bandwidth, "server", server);
            } catch (Exception e) {
                // ZLM 不可达: reachable 置 0, 流指标不打点 → gauge 保留上一轮值, 下轮恢复后刷新
                log.warn("指标采集: 节点 ZLM 不可达: {} ({})", server, e.getMessage());
            }
            metrics.gauge("vision.node.zlm.reachable", reachable ? 1 : 0, "server", server);

            if (capacity > 0) {
                metrics.gauge("vision.node.stream.capacity", capacity, "server", server);
            }
            try {
                ssrcAllocated = ssrcPool.inUseCount(server);
                metrics.gauge("vision.node.ssrc.allocated", ssrcAllocated, "server", server);
            } catch (Exception e) {
                log.warn("指标采集: SSRC 占用数读取失败(保留上一轮值): {} ({})", server, e.getMessage());
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("server", server);
        item.put("status", status);
        item.put("zlmReachable", reachable);
        item.put("streams", streams);
        item.put("viewers", viewers);
        item.put("bandwidthBytes", bandwidth);
        item.put("ssrcAllocated", ssrcAllocated);
        item.put("portUsage", portUsage(streams, capacity));
        return item;
    }

    /** rtp 流聚合结果：streams=去重流数, viewers=sum(每流 viewers), bandwidthBytes=sum(每流 max bytesSpeed) */
    private record RtpAggregation(int streams, int viewers, long bandwidthBytes) {
    }

    /**
     * 流表聚合（docs/04 §1 getMediaList）：同一条流按 schema 多条目出现。
     * 每流 viewers = 该流条目中<b>首个</b>非 null totalReaderCount（ZLM 各 schema 的读者数同源,
     * 不累加防重复计数）；每流带宽 = 该流所有 schema 条目 bytesSpeed 的 <b>MAX</b>（bytesSpeed 为
     * 该 schema 的实时速率 B/s, 取 max 防跨 schema 重复计数; 多读者场景口径实测后回写 docs/04）。
     */
    private RtpAggregation aggregateRtp(List<ZlmMediaInfo> medias) {
        Set<String> streams = new LinkedHashSet<>();
        Map<String, Integer> viewersByStream = new HashMap<>();
        Map<String, Long> bandwidthByStream = new HashMap<>();
        List<ZlmMediaInfo> items = medias == null ? List.of() : medias;
        for (ZlmMediaInfo m : items) {
            if (m == null || !RTP_APP.equals(m.app()) || m.stream() == null) {
                continue;
            }
            String stream = m.stream();
            streams.add(stream);
            if (m.totalReaderCount() != null && !viewersByStream.containsKey(stream)) {
                viewersByStream.put(stream, m.totalReaderCount());
            }
            Long speed = m.bytesSpeed();
            if (speed != null) {
                Long prev = bandwidthByStream.get(stream);
                if (prev == null || speed.longValue() > prev.longValue()) {
                    bandwidthByStream.put(stream, speed);
                }
            }
        }
        int viewers = 0;
        for (Integer v : viewersByStream.values()) {
            viewers += v == null ? 0 : v;
        }
        long bandwidth = 0L;
        for (Long b : bandwidthByStream.values()) {
            bandwidth += b == null ? 0L : b;
        }
        return new RtpAggregation(streams.size(), viewers, bandwidth);
    }

    /** 端口段容量（rtpPortEnd-rtpPortStart+1）, 任一端无效/倒置返回 0（不打 capacity 指标） */
    static int portCapacity(GbMediaServerEntity node) {
        Integer start = node.getRtpPortStart();
        Integer end = node.getRtpPortEnd();
        if (start == null || end == null || start <= 0 || end <= 0 || end < start) {
            return 0;
        }
        return end - start + 1;
    }

    /** portUsage 快照片段：capacity<=0 时 ratio 固定 0（不产生除零/误导性 100%） */
    private Map<String, Object> portUsage(Integer used, int capacity) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("used", used);
        usage.put("capacity", capacity);
        double ratio = 0.0D;
        if (capacity > 0 && used != null && used > 0) {
            ratio = Math.round(used * 10000.0D / capacity) / 10000.0D;
        }
        usage.put("ratio", ratio);
        return usage;
    }

    /** 最近一轮采集结果快照（/api/metrics/overview 消费）：collectTime + sessions/devices/nodes */
    public Map<String, Object> snapshot() {
        return latest;
    }
}
