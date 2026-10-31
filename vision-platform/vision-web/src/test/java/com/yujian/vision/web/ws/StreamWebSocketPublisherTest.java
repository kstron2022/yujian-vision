package com.yujian.vision.web.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 事件跨实例扇出单元测试（M3-B6 阶段2）: 纯 Mockito。
 * 覆盖: 事件发布到 Redis topic + 订阅投递本地会话 + Redis 失联降级本地直发 + 销毁清理订阅。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamWebSocketPublisherTest {

    @Mock
    private WsSessionRegistry registry;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RTopic topic;
    /** 泛型 captor 走 @Captor 注入, 避免 forClass((Class) ...) 裸类型强转（同包测试惯例） */
    @Captor
    private ArgumentCaptor<MessageListener<String>> listenerCaptor;

    private StreamWebSocketPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new StreamWebSocketPublisher(registry, redisson);
        when(redisson.getTopic(anyString())).thenReturn(topic);
    }

    @Test
    @DisplayName("事件扇出: streamClosed -> publish 到 topic")
    void streamClosedPublishesToTopic() {
        publisher.afterPropertiesSet();
        publisher.streamClosed("gb_play_x", "stream_lost");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(topic, timeout(1000)).publish(captor.capture());
        assertTrue(captor.getValue().contains("STREAM_CLOSED"));
    }

    @Test
    @DisplayName("订阅投递: 监听器收到 payload -> 本地在线会话收到原文 JSON")
    void listenerDeliversToLocalSessions() throws Exception {
        publisher.afterPropertiesSet();
        verify(topic).addListener(eq(String.class), listenerCaptor.capture());
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(registry.allSessions()).thenReturn(List.of(session));

        listenerCaptor.getValue().onMessage("vision:ws:events", "{\"type\":\"STREAM_CLOSED\"}");

        verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("Redis 失联降级: publish 抛异常 -> 降级本地直发(单实例语义兜底)")
    void publishFailureFallsBackToLocalDelivery() throws Exception {
        publisher.afterPropertiesSet();
        doThrow(new RuntimeException("redis down")).when(topic).publish(anyString());
        WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(registry.allSessions()).thenReturn(List.of(session));

        publisher.streamClosed("gb_play_x", "stream_lost");

        // SEND_POOL 异步: timeout 轮询等本地直发到达
        verify(session, timeout(1000)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("destroy: 移除订阅防泄漏")
    void destroyRemovesListener() {
        publisher.afterPropertiesSet();
        verify(topic).addListener(eq(String.class), any());
        publisher.destroy();
        verify(topic).removeListener(anyInt());
    }
}
