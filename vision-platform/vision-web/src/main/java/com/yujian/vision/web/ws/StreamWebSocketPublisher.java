package com.yujian.vision.web.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RTopic;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 事件推送实现（docs/03 §10）：STREAM/DEVICE/CATALOG/CHANNEL 事件广播。
 * 连接鉴权在握手层完成（StreamWsConfig + AuthHandshakeInterceptor）。
 * 评审修复 F-WS2：会话来源统一为 WsSessionRegistry（原静态 SESSIONS 已无人注册，广播会失效）。
 * M3-B6 阶段2：事件经 Redis topic 跨实例扇出——hooks/REST 常驻落备台后, 事件产生实例与
 * 前端 WS 连接所在实例（nginx 常态落 A）可以是不同实例, 单实例本地广播会丢事件;
 * 每实例订阅同一 topic 并只投递本地会话。Redis 异常时降级为本地直发（单实例语义兜底）。
 */
@Slf4j
@Component
public class StreamWebSocketPublisher implements StreamEventPublisher, InitializingBean, DisposableBean {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 跨实例扇出 topic（payload=最终 JSON 字符串, 订阅端原样转发本地 WS 会话） */
    static final String WS_EVENTS_TOPIC = "vision:ws:events";

    /** F8: 异步发送, 慢消费者不阻塞 hook 处理线程 */
    private static final ExecutorService SEND_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-push");
        t.setDaemon(true);
        return t;
    });

    private final WsSessionRegistry registry;
    private final RedissonClient redisson;
    private int listenerId = -1;

    public StreamWebSocketPublisher(WsSessionRegistry registry, RedissonClient redisson) {
        this.registry = registry;
        this.redisson = redisson;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            RTopic topic = redisson.getTopic(WS_EVENTS_TOPIC);
            listenerId = topic.addListener(String.class, (channel, payload) -> sendLocal(payload));
        } catch (Exception e) {
            // Redis 不可用: 退化为单实例本地广播（与阶段2 之前行为一致）, 不阻断启动
            listenerId = -1;
            log.warn("WS 事件订阅失败, 退化为本地广播: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (listenerId >= 0) {
            try {
                redisson.getTopic(WS_EVENTS_TOPIC).removeListener(listenerId);
            } catch (Exception e) {
                log.debug("WS 事件订阅清理失败(忽略): {}", e.getMessage());
            }
        }
    }

    @Override
    public void streamReady(String streamId, String channelId, Map<String, String> urls, String videoCodec) {
        // videoCodec 允许 null（hook 无 tracks 时）: Map.of 不收 null, 先归一为空串
        String codec = videoCodec == null ? "" : videoCodec;
        send("STREAM_READY", Map.of("streamId", streamId, "channelId", channelId,
                "urls", urls, "videoCodec", codec));
    }

    @Override
    public void streamClosed(String streamId, String reason) {
        send("STREAM_CLOSED", Map.of("streamId", streamId, "reason", reason));
    }

    @Override
    public void streamFallback(String streamId, String channelId) {
        send("STREAM_FALLBACK", Map.of("streamId", streamId, "channelId", channelId));
    }

    @Override
    public void deviceOnline(String deviceId, String name) {
        send("DEVICE_ONLINE", Map.of("deviceId", deviceId, "name", name == null ? "" : name));
    }

    @Override
    public void deviceOffline(String deviceId, String name) {
        send("DEVICE_OFFLINE", Map.of("deviceId", deviceId, "name", name == null ? "" : name));
    }

    @Override
    public void catalogSyncProgress(String deviceId, int total, int synced) {
        send("CATALOG_SYNC_PROGRESS", Map.of("deviceId", deviceId, "total", total, "synced", synced));
    }

    @Override
    public void channelChange(String deviceId) {
        send("CHANNEL_CHANGE", Map.of("deviceId", deviceId));
    }

    @Override
    public void noticeNew(Long noticeId, String type, String title, int level, String createTime) {
        send("NOTICE_NEW", Map.of("id", noticeId, "type", type, "title", title,
                "level", level, "createTime", createTime));
    }

    /** M3-B4: 报警事件广播（Map.of 不收 null, channelId/时间缺省归一为空串, 类型/级别缺省 0） */
    @Override
    public void alarmEvent(String deviceId, String channelId, Integer alarmType, Integer alarmPriority,
            String alarmTime) {
        send("ALARM", Map.of(
                "deviceId", deviceId,
                "channelId", channelId == null ? "" : channelId,
                "alarmType", alarmType == null ? 0 : alarmType,
                "alarmPriority", alarmPriority == null ? 0 : alarmPriority,
                "alarmTime", alarmTime == null ? "" : alarmTime));
    }

    /** M3-B5: 对讲状态事件广播 */
    @Override
    public void talkEvent(String talkId, String channelId, String status, String reason) {
        send("TALK", Map.of(
                "talkId", talkId,
                "channelId", channelId == null ? "" : channelId,
                "status", status == null ? "" : status,
                "reason", reason == null ? "" : reason));
    }

    /** M5-B2: 回放控制被设备拒绝（INFO 非 2xx 应答, 前端据此提示「设备不支持该控制」） */
    @Override
    public void playbackControlFailed(String streamId, int code) {
        send("PLAYBACK_CONTROL_FAILED", Map.of("streamId", streamId, "code", code));
    }

    /** 序列化后发布到扇出 topic（订阅端含本实例各自投递本地会话）; 发布失败降级本地直发 */
    private void send(String type, Map<String, Object> data) {
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of(
                    "type", type,
                    "timestamp", System.currentTimeMillis(),
                    "data", data));
        } catch (Exception e) {
            log.warn("WS 消息序列化失败: {}", type);
            return;
        }
        SEND_POOL.execute(() -> {
            if (listenerId < 0) {
                sendLocal(payload);
                return;
            }
            try {
                redisson.getTopic(WS_EVENTS_TOPIC).publish(payload);
            } catch (Exception e) {
                log.warn("WS 事件扇出失败, 降级本地直发: {} ({})", type, e.getMessage());
                sendLocal(payload);
            }
        });
    }

    /** 只投递本实例的 WS 会话（订阅回调与 Redis 失联降级共用） */
    private void sendLocal(String payload) {
        for (WebSocketSession s : registry.allSessions()) {
            try {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (Exception e) {
                log.debug("WS 发送失败(忽略): {}", e.getMessage());
            }
        }
    }
}
