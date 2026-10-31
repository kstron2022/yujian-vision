package com.yujian.vision.service.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VisionMetrics 单元测试：SimpleMeterRegistry 直连（纯 JVM, 不起 Spring 上下文）。
 * 重点覆盖 gauge 弱引用池语义（幂等覆盖/分标签序列）与 counter 累加。
 */
class VisionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final VisionMetrics metrics = new VisionMetrics(registry);

    @Test
    @DisplayName("gauge: 首次打点注册并可读取")
    void gaugeRegistersOnFirstCall() {
        metrics.gauge("vision.session.active", 3, "state", "STREAMING");

        var gauge = registry.get("vision.session.active").tag("state", "STREAMING").gauge();
        assertNotNull(gauge);
        assertEquals(3.0D, gauge.value());
    }

    @Test
    @DisplayName("gauge: 同 name+tags 幂等覆盖, 不重复注册")
    void gaugeOverwritesValueIdempotently() {
        metrics.gauge("vision.node.streams", 2, "server", "vision-zlm");
        metrics.gauge("vision.node.streams", 7, "server", "vision-zlm");

        assertEquals(7.0D, registry.get("vision.node.streams").tag("server", "vision-zlm").gauge().value());
        assertEquals(1, registry.get("vision.node.streams").meters().size());
    }

    @Test
    @DisplayName("gauge: 不同 tags 生成独立序列")
    void gaugeSeparatesSeriesByTags() {
        metrics.gauge("vision.node.status", 1, "server", "node-a");
        metrics.gauge("vision.node.status", 0, "server", "node-b");

        assertEquals(1.0D, registry.get("vision.node.status").tag("server", "node-a").gauge().value());
        assertEquals(0.0D, registry.get("vision.node.status").tag("server", "node-b").gauge().value());
    }

    @Test
    @DisplayName("gauge/counter: 奇数个 tagKv 抛 IllegalArgumentException")
    void oddTagPairsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> metrics.gauge("vision.bad", 1, "only-key"));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.increment("vision.bad.counter", "k", "v", "lonely"));
    }

    @Test
    @DisplayName("counter: 同名累加, 不同标签独立计数")
    void counterAccumulates() {
        metrics.increment("vision.hook", "event", "on_publish");
        metrics.increment("vision.hook", "event", "on_publish");
        metrics.increment("vision.hook", "event", "on_play");

        assertEquals(2.0D, registry.get("vision.hook").tag("event", "on_publish").counter().count());
        assertEquals(1.0D, registry.get("vision.hook").tag("event", "on_play").counter().count());
    }
}
