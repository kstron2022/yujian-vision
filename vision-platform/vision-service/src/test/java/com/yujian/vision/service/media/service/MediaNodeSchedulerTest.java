package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.monitor.NodeMetricsTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 点播节点调度策略单测（M3-B6 阶段1） */
class MediaNodeSchedulerTest {

    private static GbMediaServerEntity node(String id) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(id);
        n.setStatus(1);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<NodeMetricsTask> metrics(
            Map<String, Object> snap) {
        NodeMetricsTask task = Mockito.mock(NodeMetricsTask.class);
        when(task.snapshot()).thenReturn(snap);
        org.springframework.beans.factory.ObjectProvider<NodeMetricsTask> provider =
                Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(task);
        return provider;
    }

    /** 快照构造: nodes 段 */
    private static Map<String, Object> snap(Map<String, long[]> loads) {
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (Map.Entry<String, long[]> e : loads.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("server", e.getKey());
            m.put("streams", e.getValue()[0]);
            m.put("bandwidthBytes", e.getValue()[1]);
            nodes.add(m);
        }
        Map<String, Object> snap = new HashMap<>();
        snap.put("nodes", nodes);
        return snap;
    }

    @Test
    @DisplayName("无在线节点抛业务异常")
    void noOnlineNodeThrows() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());
        assertThrows(BizException.class, svc::select);
    }

    @Test
    @DisplayName("单节点直接返回(不等策略)")
    void singleNodeShortCircuit() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(node("only")));
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());
        assertEquals("only", svc.select().getServerId());
    }

    @Test
    @DisplayName("least-streams: 选在流数最少的节点")
    void leastStreamsPicksMin() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{2, 100}, "b", new long[]{1, 900}))), new MediaProperties());
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("least-streams 平流时 tie-break 带宽, 再平按 id")
    void leastStreamsTieBreak() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"), node("c"))));
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{1, 500}, "b", new long[]{1, 100}, "c", new long[]{1, 100}))),
                new MediaProperties());
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("快照为空(刚启动)所有节点未知按 0 计, id tie-break 生效")
    void emptySnapshotFallsBackToIdTieBreak() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        GbMediaServerEntity b = node("b");
        b.setId(1L);
        GbMediaServerEntity a = node("a");
        a.setId(2L);
        // 列表顺序 b 在前: 全 0 平局时 id 小者胜, 与列表顺序无关
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(b, a)));
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("ZLM 不可达节点(streams=null)排最后")
    void unreachableNodeRankedLast() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        // a 在快照里 streams=null → 不可达
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        Map<String, Object> ma = new HashMap<>();
        ma.put("server", "a");
        ma.put("streams", null);
        nodes.add(ma);
        Map<String, Object> mb = new HashMap<>();
        mb.put("server", "b");
        mb.put("streams", 5);
        mb.put("bandwidthBytes", 1L);
        nodes.add(mb);
        Map<String, Object> s = new HashMap<>();
        s.put("nodes", nodes);
        var svc = new MediaNodeScheduler(mapper, metrics(s), new MediaProperties());
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("least-bandwidth: 选实时带宽最小的节点")
    void leastBandwidthPicksMin() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        MediaProperties props = new MediaProperties();
        props.setNodeSchedule("least-bandwidth");
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{1, 5000}, "b", new long[]{3, 100}))), props);
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("first 策略: 恒取首个在线节点")
    void firstStrategyKeepsLegacyBehavior() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        MediaProperties props = new MediaProperties();
        props.setNodeSchedule("first");
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{9, 9999}, "b", new long[]{1, 1}))), props);
        assertEquals("a", svc.select().getServerId());
    }

    @Test
    @DisplayName("未识别策略值按 least-streams 兜底")
    void unknownStrategyFallsBackToLeastStreams() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        MediaProperties props = new MediaProperties();
        props.setNodeSchedule("whatever");
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{9, 0}, "b", new long[]{1, 0}))), props);
        assertEquals("b", svc.select().getServerId());
    }

    @Test
    @DisplayName("排除集生效: 已失败节点被剔除, 选中下一候选")
    void excludeSetRemovesFailedNode() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());

        assertEquals("b", svc.select(java.util.Set.of("a")).getServerId());
    }

    @Test
    @DisplayName("唯一节点被排除 → 抛 ZLM_API_FAILED(排除先于单节点短路)")
    void onlyNodeExcludedThrows() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(node("only"))));
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());

        assertThrows(BizException.class, () -> svc.select(java.util.Set.of("only")));
    }

    @Test
    @DisplayName("排除后为空 → 异常消息含被剔除节点")
    void excludedAllMessageListsFailedNodes() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        var svc = new MediaNodeScheduler(mapper, metrics(Map.of()), new MediaProperties());

        BizException ex = assertThrows(BizException.class,
                () -> svc.select(java.util.Set.of("a", "b")));
        assertTrue(ex.getMessage().contains("a"));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    @DisplayName("排除集与不可达排序共存: 剔除 a 后仍按不可达排后规则选 b")
    void excludeCoexistsWithUnreachableRanking() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"), node("c"))));
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        Map<String, Object> ma = new HashMap<>();
        ma.put("server", "a");
        ma.put("streams", null);
        nodes.add(ma);
        Map<String, Object> mc = new HashMap<>();
        mc.put("server", "c");
        mc.put("streams", 0);
        nodes.add(mc);
        Map<String, Object> mb = new HashMap<>();
        mb.put("server", "b");
        mb.put("streams", 3);
        nodes.add(mb);
        Map<String, Object> s = new HashMap<>();
        s.put("nodes", nodes);
        var svc = new MediaNodeScheduler(mapper, metrics(s), new MediaProperties());

        // 全量口径: a 不可达排最后, c(0 流)胜 b(3 流)
        assertEquals("c", svc.select().getServerId());
        // 排除 c 后: b(3 流) 胜 a(不可达)
        assertEquals("b", svc.select(java.util.Set.of("c")).getServerId());
    }

    // ---------- M3-B7: preferred pin（on_stream_not_found 必须建在请求节点上） ----------

    @Test
    @DisplayName("B7 preferred 命中: 无视策略直接选请求节点(播放器在该节点等流)")
    void preferredPinsRequestNode() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        // 策略本会选 b(0 流), 但请求节点是 a → 必须选 a
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{9, 9999}, "b", new long[]{0, 0}))), new MediaProperties());

        assertEquals("a", svc.select("a", java.util.Set.of()).getServerId());
    }

    @Test
    @DisplayName("B7 preferred 不在候选(不在线/已排除): 回落常规策略, 不抛")
    void preferredMissingFallsBackToStrategy() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{9, 0}, "b", new long[]{1, 0}))), new MediaProperties());

        // 请求节点 c 不在线 → 回落 least-streams 选 b
        assertEquals("b", svc.select("c", java.util.Set.of()).getServerId());
        // 请求节点 a 已被剔除 → 同样回落
        assertEquals("b", svc.select("a", java.util.Set.of("a")).getServerId());
    }

    @Test
    @DisplayName("B7 preferred 为空/null: 与无参 select 等价")
    void blankPreferredBehavesAsPlainSelect() {
        GbMediaServerMapper mapper = Mockito.mock(GbMediaServerMapper.class);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(
                List.of(node("a"), node("b"))));
        var svc = new MediaNodeScheduler(mapper, metrics(snap(Map.of(
                "a", new long[]{9, 0}, "b", new long[]{1, 0}))), new MediaProperties());

        assertEquals("b", svc.select(null, java.util.Set.of()).getServerId());
        assertEquals("b", svc.select("  ", java.util.Set.of()).getServerId());
    }
}
