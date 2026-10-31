package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.service.monitor.NodeMetricsTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维监控概览（docs/09 M2 #8, B4）。内存快照直出, 平台不落库 ——
 * 历史曲线与告警阈值由旁路 Prometheus + Grafana 承担, 这里只给"当下"一屏。
 */
@Tag(name = "运维监控")
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('monitor:view')")
public class MetricsOverviewController {

    /** 会话四态固定出参顺序（与 NodeMetricsTask 会话计数键一致, 缺态补 0） */
    private static final List<String> SESSION_STATES = List.of("INIT", "INVITING", "STREAMING", "CLOSING");

    private final NodeMetricsTask nodeMetricsTask;

    @Operation(summary = "监控概览快照（会话四态/设备在线/流媒体节点端口与带宽）")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(adapt(nodeMetricsTask.snapshot()));
    }

    /** snapshot() 为 null（首轮采集未跑）时返回空结构, 前端按 0/空表渲染不报错 */
    private Map<String, Object> adapt(Map<String, Object> snap) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("collectTime", snap == null ? null : snap.get("collectTime"));
        resp.put("sessions", sessions(snap == null ? null : snap.get("sessions")));
        resp.put("devices", devices(snap == null ? null : snap.get("devices")));
        resp.put("nodes", nodes(snap == null ? null : snap.get("nodes")));
        return resp;
    }

    private static Map<String, Object> sessions(Object raw) {
        Map<?, ?> src = raw instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String state : SESSION_STATES) {
            out.put(state, num(src.get(state)));
        }
        return out;
    }

    private static Map<String, Object> devices(Object raw) {
        Map<?, ?> src = raw instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", num(src.get("online")));
        out.put("total", num(src.get("total")));
        return out;
    }

    private static List<Map<String, Object>> nodes(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> n)) {
                continue;
            }
            // 键名以 NodeMetricsTask 实际产出为准（server/zlmReachable/bandwidthBytes,
            // status 为 Integer 1/0）; 备选键名留作采集侧改名的容错
            Map<?, ?> portUsage = n.get("portUsage") instanceof Map<?, ?> p ? p : Map.of();
            long capacity = num(portUsage.get("capacity"));
            // ratio 缺失时按 used/capacity 兜底（0~1 小数, 无端口段则 0）
            Object ratio = portUsage.get("ratio");
            double portRatio = ratio instanceof Number r ? r.doubleValue()
                    : capacity > 0 ? num(portUsage.get("used")) / (double) capacity : 0d;
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("serverId", str(first(n, "server", "serverId")));
            node.put("status", n.get("status"));
            node.put("reachable", Boolean.TRUE.equals(first(n, "zlmReachable", "reachable")));
            node.put("streams", num(n.get("streams")));
            node.put("capacity", capacity);
            node.put("viewers", num(n.get("viewers")));
            node.put("bandwidthBytesPerSec", num(first(n, "bandwidthBytes", "bandwidthBytesPerSec")));
            node.put("ssrcAllocated", num(n.get("ssrcAllocated")));
            node.put("portRatio", portRatio);
            out.add(node);
        }
        return out;
    }

    /** 依次取第一个非空键（采集侧键名演进的容错） */
    private static Object first(Map<?, ?> src, String... keys) {
        for (String key : keys) {
            Object v = src.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
