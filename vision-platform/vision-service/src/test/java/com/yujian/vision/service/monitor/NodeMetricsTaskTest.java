package com.yujian.vision.service.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;

import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.service.SsrcPoolService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 节点指标采集任务单元测试（docs/09 M2 #8）：纯 Mockito + SimpleMeterRegistry,
 * 不起 Spring 上下文/Redis/数据库。校验口径表中会话/设备/节点三类指标的聚合规则,
 * 特别是 bytesSpeed 跨 schema 取 MAX 与 ZLM 不可达时流指标保留上一轮值。
 */
@ExtendWith(MockitoExtension.class)
class NodeMetricsTaskTest {

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private GbMediaServerMapper serverMapper;

    @Mock
    private ZlmApiClient zlm;

    @Mock
    private SsrcPoolService ssrcPool;

    @Mock
    private com.yujian.vision.service.cascade.service.CascadeInviteService cascadeInvites;

    @Mock
    private com.yujian.vision.service.cascade.service.CascadeUpperManager cascadeUppers;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private NodeMetricsTask task;

    @BeforeEach
    void setUp() {
        task = new NodeMetricsTask(sessionMapper, deviceMapper, serverMapper, zlm, ssrcPool,
                new VisionMetrics(registry), cascadeInvites, cascadeUppers);
    }

    private GbMediaServerEntity node() {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setId(1L);
        n.setServerId("vision-zlm");
        n.setStatus(1);
        n.setRtpPortStart(30000);
        n.setRtpPortEnd(30100);
        return n;
    }

    @Test
    @DisplayName("会话 gauge: 按状态分组计数, 未出现的活跃状态置 0")
    void sessionGaugesCountByState() {
        when(sessionMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("status", "STREAMING", "cnt", 2L),
                Map.of("status", "INVITING", "cnt", 1L)));

        task.doCollect();

        assertEquals(2.0D, registry.get("vision.session.active").tag("state", "STREAMING").gauge().value());
        assertEquals(1.0D, registry.get("vision.session.active").tag("state", "INVITING").gauge().value());
        assertEquals(0.0D, registry.get("vision.session.active").tag("state", "INIT").gauge().value());
        assertEquals(0.0D, registry.get("vision.session.active").tag("state", "CLOSING").gauge().value());
    }

    @Test
    @DisplayName("设备 gauge: online 与 total 两条指标分开打点")
    void deviceGaugesOnlineAndTotal() {
        // 顺序即实现口径: 第一次 selectCount=online, 第二次=total
        when(deviceMapper.selectCount(any())).thenReturn(3L, 9L);

        task.doCollect();

        assertEquals(3.0D, registry.get("vision.device.online").gauge().value());
        assertEquals(9.0D, registry.get("vision.device.total").gauge().value());
    }

    @Test
    @DisplayName("节点 gauge: bytesSpeed 跨 schema 取 MAX, viewers 取首个非 null, SSRC 占用打点")
    void nodeGaugesAggregateStreamsAcrossSchemas() {
        when(serverMapper.selectList(any())).thenReturn(List.of(node()));
        // ZLM getMediaList 同一条流按 schema 多条目出现(app 均为 rtp, 仅 schema 不同, 本 record 不建 schema 槽):
        // 流 gb_play_1 三个条目 readers=2/3/无, bytesSpeed=1000/1500/无
        // 流 gb_play_2 仅一条 readers=null, bytesSpeed=500
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                new ZlmMediaInfo("rtp", "gb_play_1", 2, 1000L, 5000L, ""),
                new ZlmMediaInfo("rtp", "gb_play_1", 3, 1500L, 8000L, ""),
                new ZlmMediaInfo("rtp", "gb_play_1", null, null, null, ""),
                new ZlmMediaInfo("rtp", "gb_play_2", null, 500L, 100L, "")));
        when(ssrcPool.inUseCount("vision-zlm")).thenReturn(4);

        task.doCollect();

        assertEquals(1.0D, registry.get("vision.node.status").tag("server", "vision-zlm").gauge().value());
        assertEquals(1.0D, registry.get("vision.node.zlm.reachable").tag("server", "vision-zlm").gauge().value());
        assertEquals(2.0D, registry.get("vision.node.streams").tag("server", "vision-zlm").gauge().value());
        // 每流取首个非 null totalReaderCount: 流1=2, 流2 无 → 共 2 (不跨 schema 累加)
        assertEquals(2.0D, registry.get("vision.node.viewers").tag("server", "vision-zlm").gauge().value());
        // 带宽 = sum(每流 max bytesSpeed) = max(1000,1500) + 500 = 2000 (不跨 schema 重复计数)
        assertEquals(2000.0D, registry.get("vision.node.bandwidth.bytes").tag("server", "vision-zlm").gauge().value());
        assertEquals(101.0D, registry.get("vision.node.stream.capacity").tag("server", "vision-zlm").gauge().value());
        assertEquals(4.0D, registry.get("vision.node.ssrc.allocated").tag("server", "vision-zlm").gauge().value());
    }

    @Test
    @DisplayName("ZLM 不可达: reachable=0 且流指标保留上一轮值")
    void unreachableNodeKeepsLastStreamGauges() {
        when(serverMapper.selectList(any())).thenReturn(List.of(node()));
        when(zlm.listMedia("vision-zlm"))
                .thenReturn(List.of(new ZlmMediaInfo("rtp", "gb_play_1", 2, 1000L, 5000L, "")))
                .thenThrow(new RuntimeException("connect timeout"));

        task.doCollect();
        assertEquals(1.0D, registry.get("vision.node.zlm.reachable").tag("server", "vision-zlm").gauge().value());
        assertEquals(1.0D, registry.get("vision.node.streams").tag("server", "vision-zlm").gauge().value());

        task.doCollect();
        assertEquals(0.0D, registry.get("vision.node.zlm.reachable").tag("server", "vision-zlm").gauge().value());
        // 流指标不打点 → 保留上一轮值
        assertEquals(1.0D, registry.get("vision.node.streams").tag("server", "vision-zlm").gauge().value());
        assertEquals(1000.0D, registry.get("vision.node.bandwidth.bytes").tag("server", "vision-zlm").gauge().value());
    }

    @Test
    @DisplayName("离线节点/无效端口段: 不拉流表, 不打 reachable 与 capacity")
    void offlineNodeAndInvalidPortRangeSkipMetrics() {
        GbMediaServerEntity offline = node();
        offline.setStatus(0);
        GbMediaServerEntity badPorts = node();
        badPorts.setServerId("vision-zlm-2");
        badPorts.setStatus(1);
        badPorts.setRtpPortEnd(0);
        when(serverMapper.selectList(any())).thenReturn(List.of(offline, badPorts));
        when(ssrcPool.inUseCount("vision-zlm-2")).thenReturn(0);

        task.doCollect();

        assertEquals(0.0D, registry.get("vision.node.status").tag("server", "vision-zlm").gauge().value());
        assertNull(registry.find("vision.node.zlm.reachable").tag("server", "vision-zlm").gauge());
        assertNull(registry.find("vision.node.stream.capacity").tag("server", "vision-zlm").gauge());
        assertNull(registry.find("vision.node.stream.capacity").tag("server", "vision-zlm-2").gauge());
        assertEquals(1.0D, registry.get("vision.node.status").tag("server", "vision-zlm-2").gauge().value());
    }

    @Test
    @DisplayName("级联 gauge: 在途转推路数 + ONLINE 上级数, snapshot 带cascade 字段")
    void cascadeGaugesRelayAndUppers() {
        when(cascadeInvites.sendingCount()).thenReturn(2);
        when(cascadeUppers.snapshot()).thenReturn(Map.of(
                1L, Map.of("state", "ONLINE", "backoffSeconds", 0),
                2L, Map.of("state", "BACKOFF", "backoffSeconds", 40)));

        task.doCollect();

        assertEquals(2.0D, registry.get("vision.cascade.relay.active").gauge().value());
        assertEquals(1.0D, registry.get("vision.cascade.uppers.online").gauge().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> cascade = (Map<String, Object>) task.snapshot().get("cascade");
        assertEquals(2, cascade.get("relayActive"));
        assertEquals(1, cascade.get("uppersOnline"));
    }

    @Test
    @DisplayName("snapshot: collectTime/sessions/devices/nodes(含 portUsage.ratio) 字段完整")
    void snapshotExposesLastRoundValues() {
        when(serverMapper.selectList(any())).thenReturn(List.of(node()));
        when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                new ZlmMediaInfo("rtp", "gb_play_1", 2, 1000L, 5000L, "")));
        when(deviceMapper.selectCount(any())).thenReturn(1L, 2L);
        when(ssrcPool.inUseCount("vision-zlm")).thenReturn(1);
        when(sessionMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("status", "STREAMING", "cnt", 1L)));

        task.doCollect();

        Map<String, Object> snap = task.snapshot();
        assertNotNull(snap.get("collectTime"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = (Map<String, Object>) snap.get("sessions");
        assertEquals(1L, sessions.get("STREAMING"));
        assertEquals(0L, sessions.get("INIT"));

        @SuppressWarnings("unchecked")
        Map<String, Object> devices = (Map<String, Object>) snap.get("devices");
        assertEquals(1L, devices.get("online"));
        assertEquals(2L, devices.get("total"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) snap.get("nodes");
        assertEquals(1, nodes.size());
        Map<String, Object> entry = nodes.get(0);
        assertEquals("vision-zlm", entry.get("server"));
        assertEquals(1, entry.get("status"));
        assertEquals(Boolean.TRUE, entry.get("zlmReachable"));
        assertEquals(1, entry.get("streams"));
        assertEquals(1000L, entry.get("bandwidthBytes"));

        @SuppressWarnings("unchecked")
        Map<String, Object> portUsage = (Map<String, Object>) entry.get("portUsage");
        assertEquals(1, portUsage.get("used"));
        assertEquals(101, portUsage.get("capacity"));
        assertTrue((Double) portUsage.get("ratio") > 0.0D && (Double) portUsage.get("ratio") < 0.01D);
    }
}
