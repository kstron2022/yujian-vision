package com.yujian.vision.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 可信代理解析单测：追加式 XFF 从右向左取第一个非可信地址/伪造首段被忽略/全可信回退 remoteAddr/
 * 精确命中/IPv4 CIDR 命中/非可信忽略 XFF/无 XFF 回退 remoteAddr/缺省空列表忽略 XFF。
 */
class ClientIpResolverTest {

    private TrustedProxyProperties props;
    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        props = new TrustedProxyProperties();
        resolver = new ClientIpResolver(props);
    }

    @Test
    @DisplayName("直连命中精确可信代理(127.0.0.1) + 客户端伪造首段被 nginx 追加 → 取最右非可信地址, 伪造失效")
    void exactTrustedProxyIgnoresSpoofedFirstSegment() {
        props.getTrustedProxies().add("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        // 客户端自带 "203.0.113.7", nginx $proxy_add_x_forwarded_for 追加真实来源后 → 该形态
        request.addHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.9");

        assertEquals("198.51.100.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("直连命中精确可信代理 + 诚实客户端无伪造(单段) → 采信该段")
    void trustedProxyHonestSingleSegmentXff() {
        props.getTrustedProxies().add("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.9");

        assertEquals("198.51.100.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("XFF 全部为可信代理地址(多层内网代理) → 回退 remoteAddr")
    void allTrustedXffFallsBackToRemoteAddr() {
        props.getTrustedProxies().add("127.0.0.1");
        props.getTrustedProxies().add("10.0.0.0/8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.1.2.3, 10.4.5.6");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    @DisplayName("直连命中 IPv4 CIDR(172.16.0.0/12) → 取 XFF 最右非可信地址")
    void cidrTrustedProxyTrustsXff() {
        props.getTrustedProxies().add("172.16.0.0/12");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.17.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    @DisplayName("直连非可信代理 → XFF 被忽略, 取 remoteAddr(防伪造绕过限流/防爆破)")
    void untrustedProxyIgnoresXff() {
        props.getTrustedProxies().add("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("8.8.8.8");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("8.8.8.8", resolver.resolve(request));
    }

    @Test
    @DisplayName("无 XFF → 回退 remoteAddr")
    void noXffFallsBackToRemoteAddr() {
        props.getTrustedProxies().add("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("9.9.9.9");

        assertEquals("9.9.9.9", resolver.resolve(request));
    }

    @Test
    @DisplayName("缺省空列表 → 一律不信任 XFF")
    void defaultEmptyListIgnoresXff() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    @DisplayName("CIDR 匹配: 172.16.0.0/12 命中 172.17.0.1; 不命中 172.32.0.1/非法输入")
    void cidrMatchingDetails() {
        assertTrue(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "12", "172.17.0.1"));
        assertTrue(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "12", "172.31.255.255"));
        assertFalse(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "12", "172.32.0.1"));
        // IPv4 CIDR 对 IPv6 候选/非法前缀一律不命中
        assertFalse(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "12", "::1"));
        assertFalse(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "99", "172.17.0.1"));
        assertFalse(ClientIpResolver.matchesIpv4Cidr("172.16.0.0", "abc", "172.17.0.1"));
        // IPv6 字面量精确比对走 isTrustedProxy 分支
        assertFalse(resolver.isTrustedProxy("::1"));
        props.getTrustedProxies().add("::1");
        assertTrue(resolver.isTrustedProxy("::1"));
    }
}
