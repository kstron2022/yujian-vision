package com.yujian.vision.service.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.yujian.vision.service.system.service.NoticeService;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * Alertmanager v2 webhook 告警转站内通知（docs/09 M2 #8）。
 *
 * <p>Prometheus/Alertmanager 旁路部署（Docker）产生的告警经 webhook 汇入平台消息中心：
 * alerts 数组逐条处理, 仅 status=firing 发通知（resolved 不发 —— 恢复由 Grafana 面板体现,
 * 不制造"已恢复"噪音）; level 按 labels.severity 映射（critical=2/warning=1, 对齐 sys_notice.level
 * 1=信息 2=重要）; bizId=fingerprint 作 30 分钟去重键（Alertmanager repeat_interval 小于去重窗口时
 * 不刷屏）。</p>
 *
 * <p>旁路原则（docs/03 第 2.2 节 同消息中心）：任何解析/去重/发布异常自吞并 log.warn,
 * 绝不影响 webhook 响应与主业务。</p>
 */
@Slf4j
@Service
public class AlertNoticeService {

    /** 通知 type（命名沿用现有告警类通知大写下划线风格: DEVICE_OFFLINE/CATALOG_SYNC_FAIL） */
    public static final String NOTICE_TYPE = "PLATFORM_ALERT";
    /** 去重键前缀（bizId=fingerprint, 30 分钟窗口） */
    public static final String DEDUP_KEY = "gb:notice:alert:";
    /** 30 分钟去重窗口 */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(30);
    /** content 列宽 512, 预留余量截断 */
    private static final int CONTENT_MAX = 480;
    /** title 列宽 128, 预留余量截断 */
    private static final int TITLE_MAX = 120;
    /** biz_id 列宽 64 */
    private static final int BIZ_ID_MAX = 64;
    /** severity → 通知 level 映射（1=信息 2=重要） */
    private static final Map<String, Integer> SEVERITY_LEVEL =
            Map.of("critical", 2, "warning", 1);

    private final NoticeService notices;
    private final RedissonClient redisson;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public AlertNoticeService(NoticeService notices, RedissonClient redisson,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.notices = notices;
        this.redisson = redisson;
        this.mapper = mapper;
    }

    /**
     * 处理 Alertmanager v2 webhook 报文（POST body）。
     * 报文形状: {"alerts":[{"status","labels":{...},"annotations":{...},"startsAt","endsAt","fingerprint"}]}
     * 整体 try/catch: 畸形 JSON/缺字段只记日志, 对调用方永远不抛。
     */
    public void handle(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode alerts = mapper.readTree(body).path("alerts");
            if (!alerts.isArray()) {
                log.warn("告警 webhook 报文无 alerts 数组(忽略): {}", truncate(body, 200));
                return;
            }
            for (JsonNode alert : alerts) {
                try {
                    handleOne(alert);
                } catch (Exception e) {
                    log.warn("单条告警处理失败(继续下一条): {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("告警 webhook 报文解析失败(忽略): {}", e.getMessage());
        }
    }

    /** 单条告警: resolved 不发; firing 按指纹 30 分钟去重后转站内通知 */
    private void handleOne(JsonNode alert) {
        String status = alert.path("status").asText("");
        if (!"firing".equalsIgnoreCase(status)) {
            // resolved/非 firing 状态不发通知（恢复态以监控面板为准）
            return;
        }
        String alertname = alert.path("labels").path("alertname").asText("未知告警");
        String fingerprint = fingerprintOf(alert, alertname);
        String severity = alert.path("labels").path("severity").asText("warning");
        int level = SEVERITY_LEVEL.getOrDefault(severity.toLowerCase(), 1);
        String description = alert.path("annotations").path("description").asText("");
        String content = description.isBlank() ? labelsSummary(alert.path("labels")) : description;
        if (publishDeduped(fingerprint)) {
            notices.publish(NOTICE_TYPE, truncate("告警: " + alertname, TITLE_MAX),
                    truncate(content, CONTENT_MAX), level, truncate(fingerprint, BIZ_ID_MAX));
        }
    }

    /**
     * fingerprint 去重发布: setIfAbsent 命中(本次先到)才发通知, 30 分钟内同指纹只发一条。
     * Redis 异常按 fail-open 处理（照常发布）: 告警可见性优先于刷屏抑制, 只记 warn。
     *
     * @return true=已发布 / false=去重命中静默
     */
    private boolean publishDeduped(String fingerprint) {
        try {
            RBucket<String> bucket = redisson.getBucket(DEDUP_KEY + fingerprint);
            return bucket.setIfAbsent("1", DEDUP_TTL);
        } catch (Exception e) {
            log.warn("告警去重键写入失败(fail-open 照常发布): fingerprint={} err={}", fingerprint, e.getMessage());
            return true;
        }
    }

    /** fingerprint 缺失时以 alertname+startsAt 兜底（Alertmanager 对无指纹告警仍可去重） */
    private static String fingerprintOf(JsonNode alert, String alertname) {
        String fp = alert.path("fingerprint").asText("");
        if (!fp.isBlank()) {
            return fp;
        }
        return truncate(alertname + "@" + alert.path("startsAt").asText(""), BIZ_ID_MAX);
    }

    /** annotations.description 缺失时的 labels 摘要兜底: k=v 按字典序拼接 */
    private static String labelsSummary(JsonNode labels) {
        if (labels == null || !labels.isObject()) {
            return "无标签信息";
        }
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : labels.properties()) {
            sorted.put(e.getKey(), e.getValue().asText(""));
        }
        if (sorted.isEmpty()) {
            return "无标签信息";
        }
        StringBuilder sb = new StringBuilder("labels: ");
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 8) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
