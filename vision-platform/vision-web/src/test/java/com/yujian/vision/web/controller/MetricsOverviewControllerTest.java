package com.yujian.vision.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yujian.vision.service.monitor.NodeMetricsTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MetricsOverviewController 单测（评审 B4-#5）：首轮空快照归一为空结构,
 * 正常快照的键名适配（server/zlmReachable/bandwidthBytes/status Integer → 契约键）。
 */
class MetricsOverviewControllerTest {

    private final NodeMetricsTask task = mock(NodeMetricsTask.class);
    private final MetricsOverviewController controller = new MetricsOverviewController(task);

    @Test
    @DisplayName("首轮快照为空 → 空结构不报错")
    void nullSnapshotYieldsEmptyStructure() {
        when(task.snapshot()).thenReturn(null);
        Map<String, Object> data = controller.overview().getData();
        assertNull(data.get("collectTime"));
        assertEquals(Map.of("INIT", 0L, "INVITING", 0L, "STREAMING", 0L, "CLOSING", 0L), data.get("sessions"));
        assertEquals(Map.of("online", 0L, "total", 0L), data.get("devices"));
        assertTrue(((List<?>) data.get("nodes")).isEmpty());
    }

    @Test
    @DisplayName("正常快照适配: 采集键名 → 契约键名, portUsage 平铺为 portRatio")
    void snapshotAdaptedToContract() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("server", "vision-zlm");
        node.put("status", 1);
        node.put("zlmReachable", true);
        node.put("streams", 2);
        node.put("viewers", 2);
        node.put("bandwidthBytes", 900000L);
        node.put("ssrcAllocated", 1);
        node.put("portUsage", Map.of("used", 2, "capacity", 501, "ratio", 0.004));
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("collectTime", "2026-09-11 10:00:00");
        snap.put("sessions", Map.of("STREAMING", 2));
        snap.put("devices", Map.of("online", 1, "total", 2));
        snap.put("nodes", List.of(node));
        when(task.snapshot()).thenReturn(snap);

        Map<String, Object> data = controller.overview().getData();
        assertEquals("2026-09-11 10:00:00", data.get("collectTime"));
        assertEquals(2L, ((Map<?, ?>) data.get("sessions")).get("STREAMING"));
        assertEquals(1L, ((Map<?, ?>) data.get("devices")).get("online"));
        Map<?, ?> adapted = (Map<?, ?>) ((List<?>) data.get("nodes")).get(0);
        assertEquals("vision-zlm", adapted.get("serverId"));
        assertEquals(Boolean.TRUE, adapted.get("reachable"));
        assertEquals(501L, adapted.get("capacity"));
        assertEquals(900000L, adapted.get("bandwidthBytesPerSec"));
        assertEquals(0.004, ((Number) adapted.get("portRatio")).doubleValue(), 1e-9);
        assertFalse(adapted.containsKey("portUsage"));
    }
}
