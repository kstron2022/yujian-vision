package com.yujian.vision.web.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.security.Sm3;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.service.OpenApiLogService;
import com.yujian.vision.service.openapi.service.OpenAppService;
import com.yujian.vision.web.ratelimit.RateLimitStore;
import com.yujian.vision.web.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 开放接口签名过滤器单测（docs/15 步骤3, 验收#1 五类负例）：真实 Sm3/HMAC-SM3 + MockHttpServletRequest,
 * mock OpenAppService/Redisson/RateLimitStore, 不起 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class OpenApiSignatureFilterTest {

    private static final String SECRET = "test-secret-48chars-AAAAAAAAAAAAAAAAAAAAA";
    private static final String APP_KEY = "0123456789abcdef0123456789abcdef";
    private static final String PATH = "/openapi/v1/devices";

    @Mock
    private OpenAppService appService;
    @Mock
    private OpenApiLogService logService;
    @Mock
    private RateLimitStore rateLimitStore;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RBucket<String> nonceBucket;
    @Mock
    private FilterChain chain;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /** 标准签名请求构造：4 头 + 正确 HMAC-SM3 签名（传 signature=null 时自动算正确签名） */
    private MockHttpServletRequest signedGet(String ts, String nonce, String signature) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", PATH);
        req.setRequestURI(PATH);
        req.addHeader("X-App-Key", APP_KEY);
        req.addHeader("X-Timestamp", ts);
        req.addHeader("X-Nonce", nonce);
        req.addHeader("X-Signature", signature == null ? validSign(ts, nonce, "") : signature);
        return req;
    }

    /** 计算正确签名（可指定 body） */
    private String validSign(String ts, String nonce, String body) {
        String signString = "GET\n" + PATH + "\n" + ts + "\n" + nonce + "\n"
                + Sm3.digestHex(body.getBytes(StandardCharsets.UTF_8));
        return Sm3.hmacBase64(SECRET, signString.getBytes(StandardCharsets.UTF_8));
    }

    private OpenAppAuth app(Set<String> apis, int rateLimit) {
        com.yujian.vision.service.openapi.entity.OpenAppEntity e =
                new com.yujian.vision.service.openapi.entity.OpenAppEntity();
        e.setId(7L);
        e.setAppKey(APP_KEY);
        e.setAppSecret(SECRET);
        e.setAllowedApis(String.join(",", apis));
        e.setChannelScope("");
        e.setRateLimit(rateLimit);
        e.setStatus(1);
        return OpenAppAuth.from(e, SECRET, "hmac-sm3");
    }

    private OpenApiSignatureFilter filter() {
        return new OpenApiSignatureFilter(appService, logService, rateLimitStore, clientIpResolver,
                new com.fasterxml.jackson.databind.ObjectMapper(), redisson, "hmac-sm3");
    }

    private void stubHappyRedis() {
        doReturn(nonceBucket).when(redisson).getBucket(anyString(), any(StringCodec.class));
        when(nonceBucket.setIfAbsent(anyString(), any(java.time.Duration.class))).thenReturn(true);
    }

    private String nowTs() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    @Test
    @DisplayName("shouldNotFilter: 非 /openapi/ 路径跳过")
    void skipsNonOpenApiPaths() throws Exception {
        OpenApiSignatureFilter f = filter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/devices");
        req.setRequestURI("/api/devices");
        assertTrue(f.shouldNotFilter(req));
        assertFalse(f.shouldNotFilter(signedGet(nowTs(), "n0", null)));
    }

    @Test
    @DisplayName("正例: 签名正确 GET → 放行进 chain, context 有令牌, 日志记录响应码 200")
    void validSignaturePasses() throws Exception {
        OpenApiSignatureFilter f = filter();
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of(), 10));
        when(rateLimitStore.increment(anyString(), anyInt())).thenReturn(1L);

        MockHttpServletRequest req = signedGet(nowTs(), "n1", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        // chain 内当场断言: filter 在 chain 返回后清理 context, 必须在调用瞬间验证;
        // 并模拟 controller 写 R 包络 —— 调用日志的 result_code 取包络 code(=0)
        doAnswer(inv -> {
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertTrue(principal instanceof OpenAppAuth);
            jakarta.servlet.ServletResponse resp = inv.getArgument(1);
            resp.getWriter().write("{\"code\":0,\"msg\":\"success\",\"data\":null}");
            return null;
        }).when(chain).doFilter(any(), any());
        f.doFilter(req, res, chain);

        verify(chain).doFilter(any(), any());
        verify(logService).record(org.mockito.ArgumentMatchers.eq(7L), eq(APP_KEY), eq("GET"), eq(PATH),
                eq(""), eq(0), eq("1.2.3.4"), org.mockito.ArgumentMatchers.anyInt(), eq(""));
    }

    @Test
    @DisplayName("负例-缺头: 4 头任一缺失 → 401 不进 chain")
    void missingHeadersRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", PATH);
        req.setRequestURI(PATH);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("负例-过期: 时间戳超 ±300s → 401")
    void staleTimestampRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        String oldTs = String.valueOf(System.currentTimeMillis() / 1000 - 301);
        MockHttpServletRequest req = signedGet(oldTs, "n2", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("负例-重放: nonce SETNX 失败 → 401(合法 app 才到 nonce 步)")
    void replayedNonceRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of(), 10));
        when(redisson.getBucket(anyString(), any(StringCodec.class)))
                .thenAnswer(inv -> nonceBucket);
        when(nonceBucket.setIfAbsent(anyString(), any(java.time.Duration.class))).thenReturn(false);
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        MockHttpServletRequest req = signedGet(nowTs(), "dup", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("负例-app无效: loadAuth null(停用/过期/不存在) → 401(先于 nonce, 不写 Redis)")
    void invalidAppRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(null);
        MockHttpServletRequest req = signedGet(nowTs(), "n3", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        verify(redisson, never()).getBucket(anyString(), any(StringCodec.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("负例-签名错误: 篡改签名 → 401")
    void badSignatureRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of(), 10));
        MockHttpServletRequest req = signedGet(nowTs(), "n4", "WRONGSIGNATURE==");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    /** 构造 POST 签名请求（白名单/频控负例用） */
    private MockHttpServletRequest signedPost(String ts, String nonce, String signature) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/openapi/v1/play");
        req.setRequestURI("/openapi/v1/play");
        req.addHeader("X-App-Key", APP_KEY);
        req.addHeader("X-Timestamp", ts);
        req.addHeader("X-Nonce", nonce);
        String body = "";
        String signString = "POST\n/openapi/v1/play\n" + ts + "\n" + nonce + "\n"
                + Sm3.digestHex(body.getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-Signature", signature == null
                ? Sm3.hmacBase64(SECRET, signString.getBytes(StandardCharsets.UTF_8)) : signature);
        return req;
    }

    @Test
    @DisplayName("负例-白名单: 空白名单 POST → 403; 白名单不含该路径 → 403")
    void writeWithoutWhitelistRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of(), 10));

        MockHttpServletRequest post = signedPost(nowTs(), "n5", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(post, res, chain);
        assertEquals(403, res.getStatus());
        verify(chain, never()).doFilter(any(), any());

        // 白名单含 /openapi/v1/play 之外的路径: POST /play 仍 403
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of("/openapi/v1/ptz/control"), 10));
        MockHttpServletRequest post2 = signedPost(nowTs(), "n6", null);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        f2chain(post2, res2, f);
        assertEquals(403, res2.getStatus());
    }

    private void f2chain(MockHttpServletRequest req, MockHttpServletResponse res, OpenApiSignatureFilter f)
            throws Exception {
        f.doFilter(req, res, chain);
    }

    @Test
    @DisplayName("负例-频控: 秒窗口计数超 rateLimit → 429")
    void rateLimitedRejected() throws Exception {
        OpenApiSignatureFilter f = filter();
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of("/openapi/v1/play"), 2));
        when(rateLimitStore.increment(anyString(), anyInt())).thenReturn(3L);

        MockHttpServletRequest post = signedPost(nowTs(), "n7", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(post, res, chain);
        assertEquals(429, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Redis 故障 fail-closed: nonce 阶段/频控阶段异常 → 503 拒绝")
    void redisFailureFailsClosed() throws Exception {
        OpenApiSignatureFilter f = filter();
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of(), 10));
        when(redisson.getBucket(anyString(), any(StringCodec.class)))
                .thenThrow(new org.redisson.client.RedisException("down"));
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        MockHttpServletRequest req = signedGet(nowTs(), "n8", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertEquals(503, res.getStatus());
        verify(chain, never()).doFilter(any(), any());

        // 频控阶段 Redis 异常 → 503
        OpenApiSignatureFilter f2 = filter();
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sm3")).thenReturn(app(Set.of("/openapi/v1/play"), 10));
        when(rateLimitStore.increment(anyString(), anyInt()))
                .thenThrow(new org.redisson.client.RedisException("down"));
        MockHttpServletRequest post = signedPost(nowTs(), "n9", null);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        f2chain(post, res2, f2);
        assertEquals(503, res2.getStatus());
    }

    @Test
    @DisplayName("hmac-sha256 回退算法: 正确签名通过")
    void sha256FallbackWorks() throws Exception {
        OpenApiSignatureFilter f = new OpenApiSignatureFilter(appService, logService, rateLimitStore,
                clientIpResolver, new com.fasterxml.jackson.databind.ObjectMapper(), redisson, "hmac-sha256");
        stubHappyRedis();
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(appService.loadAuth(APP_KEY, "hmac-sha256")).thenReturn(app(Set.of(), 10));
        when(rateLimitStore.increment(anyString(), anyInt())).thenReturn(1L);

        String ts = nowTs();
        String nonce = "n10";
        String signString = "GET\n" + PATH + "\n" + ts + "\n" + nonce + "\n"
                + Sm3.digestHex(new byte[0]);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sign = java.util.Base64.getEncoder().encodeToString(
                mac.doFinal(signString.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest req = signedGet(ts, nonce, sign);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("非法算法构造 fail-fast")
    void badAlgorithmFailsFast() {
        try {
            new OpenApiSignatureFilter(appService, logService, rateLimitStore, clientIpResolver,
                    new com.fasterxml.jackson.databind.ObjectMapper(), redisson, "hmac-md5");
            throw new AssertionError("expect IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
