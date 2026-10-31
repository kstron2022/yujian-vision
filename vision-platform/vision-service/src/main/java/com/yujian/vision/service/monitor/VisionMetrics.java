package com.yujian.vision.service.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 平台业务指标门面（docs/09 M2 #8）：业务代码一行打点, registry 由 vision-web 的
 * spring-boot-starter-actuator + micrometer-registry-prometheus 提供（/actuator/prometheus 暴露,
 * application.yaml 已设 metrics.tags.application=vision-platform）。
 *
 * <p><b>为什么 gauge 必须自持强引用池</b>：Micrometer 的 {@code MeterRegistry.gauge(name, obj)}
 * 内部用 {@link java.lang.ref.WeakReference} 包住状态对象——调用方若每次 new 一个 Number 传进去,
 * 没有任何强引用指向它, 下次 GC 后 gauge 取值变 NaN（Prometheus 上表现为指标断点/消失）。
 * 因此这里用 {@code Map<唯一键, AtomicLong>} 长持强引用：同 name+tags 只注册一次 gauge,
 * 之后仅覆盖 AtomicLong 的值——既保证幂等覆盖, 又彻底规避弱引用回收。</p>
 *
 * <p>counter 无此问题：registry 自身强引用全部 meter, {@link #increment} 每次取回同一个
 * Counter 累加即可（重复 {@code registry.counter(...)} 取回的是已注册实例, 无重复注册开销）。</p>
 *
 * <p>tagKv 为 key,value 交替的变长参数, 必须成对；奇数个直接抛
 * {@link IllegalArgumentException}（打点错误尽早暴露, 不静默丢指标）。</p>
 */
@Component
public class VisionMetrics {

    /** gauge 强引用池: key = name|k=v|k=v（tags 排序后拼接, 同 name+tags 命中同一条目） */
    private final Map<String, AtomicLong> gaugePool = new ConcurrentHashMap<>();

    private final MeterRegistry registry;

    public VisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 打点 gauge（幂等覆盖）：同 name+tags 仅首次注册 gauge, 之后只覆盖值。
     *
     * @param name  指标名（点分小写, 如 vision.node.streams）
     * @param value 指标值
     * @param tagKv label 键值对, 必须成对
     * @throws IllegalArgumentException tagKv 不成对
     */
    public void gauge(String name, long value, String... tagKv) {
        requirePairs(name, tagKv);
        AtomicLong holder = gaugePool.computeIfAbsent(meterKey(name, tagKv), k -> registerGauge(name, tagKv));
        holder.set(value);
    }

    /**
     * 打点 counter（累加）：事件型指标（hook 事件率/INVITE 计数/登录计数等）。
     *
     * @throws IllegalArgumentException tagKv 不成对
     */
    public void increment(String name, String... tagKv) {
        requirePairs(name, tagKv);
        registry.counter(name, tagKv).increment();
    }

    /** 首次注册：AtomicLong 由 gaugePool 长持强引用, gauge 只读它 */
    private AtomicLong registerGauge(String name, String... tagKv) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge.builder(name, holder, AtomicLong::get).tags(tagKv).register(registry);
        return holder;
    }

    /**
     * 摘除 gauge（节点删除治理, 评审 B4-#1）：同步清持有池与 registry。
     * 不治理则旧 server 标签序列永久残留且停在最后值（幽灵节点虚高在线数/容量面板）。
     * Micrometer 1.15 已无 remove(String, Iterable<Tag>) 重载, 以合成 Meter.Id 按 name+tags 命中
     * （Meter.Id 的 equals/hashCode 只看 name+tags, type/unit 传 null 不影响命中）。
     */
    public void remove(String name, String... tagKv) {
        requirePairs(name, tagKv);
        gaugePool.remove(meterKey(name, tagKv));
        registry.remove(new Meter.Id(name, Tags.of(tagKv), null, null, Meter.Type.GAUGE));
    }

    /**
     * 已注册的某指标某标签键全部取值集合（节点治理比对用, 评审 B4-#1）。
     * 按 meterKey 的 "name|k=v|…" 格式解析；值里含 | 或 = 的标签不受支持（serverId 语义不含）。
     */
    public java.util.Set<String> tagValues(String name, String tagKey) {
        java.util.Set<String> values = new java.util.HashSet<>();
        String prefix = name + "|";
        for (String k : gaugePool.keySet()) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            for (String part : k.substring(prefix.length()).split("\\|", -1)) {
                int eq = part.indexOf('=');
                if (eq > 0 && tagKey.equals(part.substring(0, eq))) {
                    values.add(part.substring(eq + 1));
                }
            }
        }
        return values;
    }

    private static void requirePairs(String name, String... tagKv) {
        if (tagKv == null || tagKv.length % 2 != 0) {
            throw new IllegalArgumentException("tagKv 必须成对(key,value,...): " + name);
        }
    }

    /** 池键: name + 排序后的 tags, 保证 tag 传入顺序不影响命中同一条目 */
    private static String meterKey(String name, String... tagKv) {
        Map<String, String> tags = new TreeMap<>();
        for (int i = 0; i + 1 < tagKv.length; i += 2) {
            tags.put(tagKv[i], tagKv[i + 1]);
        }
        StringBuilder sb = new StringBuilder(name);
        for (Map.Entry<String, String> e : tags.entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
