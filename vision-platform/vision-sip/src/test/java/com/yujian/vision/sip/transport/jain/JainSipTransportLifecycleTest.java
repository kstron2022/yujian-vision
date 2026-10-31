package com.yujian.vision.sip.transport.jain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.sip.config.SipStackProperties;
import com.yujian.vision.sip.transport.SipStackDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * jain-sip 栈生命周期单测（M3-B6 阶段2）：HA 接管/回切要求同端口 stop→start 可重绑
 * （内部栈名随机代号防 SipFactory 栈表重名）; autostart=false 时 autoStart 不绑口。
 * 绑 127.0.0.1 高位口, 不触 5060。
 */
class JainSipTransportLifecycleTest {

    private static final int PORT = 5069;

    private JainSipTransportProvider provider(boolean autostart) {
        SipStackProperties props = new SipStackProperties();
        props.setAutostart(autostart);
        SipStackProperties.StackSpec spec = new SipStackProperties.StackSpec();
        spec.setStackId("main");
        spec.setHost("127.0.0.1");
        spec.setPort(PORT);
        spec.setTransport("UDP");
        props.setStacks(new java.util.ArrayList<>(java.util.List.of(spec)));
        return new JainSipTransportProvider(props);
    }

    private static SipStackDescriptor desc() {
        return new SipStackDescriptor("main", "127.0.0.1", PORT, "UDP");
    }

    @Test
    @DisplayName("同端口 stop→start 重绑成功(HA 接管/回切前提)")
    void rebindSamePortAfterStop() {
        JainSipTransportProvider p = provider(true);

        p.start(desc());
        assertEquals(1, p.activeStacks());
        assertTrue(p.isRunning("main"));

        p.stop("main");
        assertEquals(0, p.activeStacks());
        assertFalse(p.isRunning("main"));

        p.start(desc());
        assertEquals(1, p.activeStacks());
        assertTrue(p.isRunning("main"));

        p.stop("main");
    }

    @Test
    @DisplayName("autostart=false → autoStart 不绑口(HA 备台)")
    void autostartDisabledDoesNotBind() {
        JainSipTransportProvider p = provider(false);

        p.autoStart();

        assertEquals(0, p.activeStacks());
    }

    @Test
    @DisplayName("autostart=true → autoStart 拉起全部配置栈")
    void autostartBindsConfiguredStacks() {
        JainSipTransportProvider p = provider(true);

        p.autoStart();
        try {
            assertEquals(1, p.activeStacks());
        } finally {
            p.shutdown();
        }
    }
}
