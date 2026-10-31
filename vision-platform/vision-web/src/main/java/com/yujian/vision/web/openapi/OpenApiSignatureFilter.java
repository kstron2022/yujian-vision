package com.yujian.vision.web.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.security.Sm3;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.service.OpenApiLogService;
import com.yujian.vision.service.openapi.service.OpenAppService;
import com.yujian.vision.web.ratelimit.RateLimitStore;
import com.yujian.vision.web.security.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 开放接口签名认证过滤器（docs/15 步骤3）：仅拦 /openapi/**, 置于 JWT 过滤器之前。
 * <pre>
 * 签名串 = METHOD \n PATH \n timestamp \n nonce \n SM3Hex(rawBody)
 * X-Signature = Base64(HMAC-SM3|HMAC-SHA256(app_secret明文, 签名串))
 * </pre>
 * 校验链：4 头齐全 → 时间窗 ±5min → Redis SETNX nonce 防重放 → app 有效(启用/未过期) →
 * 恒定时间比对签名 → 白名单授权(空=仅 GET 只读) → QPS 频控(429, 复用 1009 口径)。
 * Redis 故障一律 fail-closed 拒绝服务（安全优先, docs/15 风险表）。
 * 仅 vision.openapi.enabled=true 时装配（D7: 未启用零行为）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenApiSignatureFilter extends OncePerRequestFilter {

    public static final String OPENAPI_APP_ATTRIBUTE = "openapi.app";
    private static final String HEADER_APP_KEY = "X-App-Key";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_NONCE = "X-Nonce";
    private static final String HEADER_SIGNATURE = "X-Signature";
    private static final long TIMESTAMP_WINDOW_SECONDS = 300;
    private static final String ALGORITHM_SM3 = "hmac-sm3";
    private static final String ALGORITHM_SHA256 = "hmac-sha256";
    /** 只读放行的 HTTP 方法（白名单空=仅只读接口） */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

    private final OpenAppService appService;
    private final OpenApiLogService logService;
    private final RateLimitStore rateLimitStore;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final org.redisson.api.RedissonClient redisson;
    private final String algorithm;

    public OpenApiSignatureFilter(OpenAppService appService, OpenApiLogService logService,
            RateLimitStore rateLimitStore, ClientIpResolver clientIpResolver, ObjectMapper objectMapper,
            org.redisson.api.RedissonClient redisson,
            @Value("${vision.openapi.signature-algorithm:hmac-sm3}") String algorithm) {
        this.appService = appService;
        this.logService = logService;
        this.rateLimitStore = rateLimitStore;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
        if (!ALGORITHM_SM3.equals(algorithm) && !ALGORITHM_SHA256.equals(algorithm)) {
            throw new IllegalStateException(
                    "vision.openapi.signature-algorithm 仅支持 hmac-sm3/hmac-sha256, 当前: " + algorithm);
        }
        this.algorithm = algorithm;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/openapi/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String clientIp = clientIpResolver.resolve(request);
        String appKey = request.getHeader(HEADER_APP_KEY);

        RepeatableBodyRequestWrapper wrapper;
        try {
            wrapper = new RepeatableBodyRequestWrapper(request);
        } catch (IOException e) {
            reject(response, 400, ErrorCode.BAD_REQUEST, "请求体读取失败", null, appKey, method, path,
                    clientIp, start);
            return;
        }

        // 1. 4 头齐全（docs/15 步骤3-1）
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (!StringUtils.hasText(appKey) || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            reject(response, 401, ErrorCode.UNAUTHORIZED, "缺少签名请求头", null, appKey, method, path,
                    clientIp, start);
            return;
        }

        // 2. 时间窗 ±5min（防长时重放/时漂）
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            reject(response, 401, ErrorCode.UNAUTHORIZED, "时间戳格式非法", null, appKey, method, path,
                    clientIp, start);
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > TIMESTAMP_WINDOW_SECONDS) {
            reject(response, 401, ErrorCode.UNAUTHORIZED, "时间戳超出允许窗口", null, appKey, method, path,
                    clientIp, start);
            return;
        }

        // 3. app 有效性（无效/停用/过期 → null; SM4 解密失败 fail-closed）。
        // 移到 nonce 之前（三视角评审 P2）: 未认证请求不写 Redis, 收敛预认证 DoS 面
        OpenAppAuth app;
        try {
            app = appService.loadAuth(appKey.trim(), algorithm);
        } catch (Exception e) {
            log.error("OpenAPI app_secret 解密失败, 拒绝(疑似密文篡改或密钥轮换未迁移): appKey={}", appKey, e);
            reject(response, 401, ErrorCode.UNAUTHORIZED, "应用凭证无效", null, appKey, method, path,
                    clientIp, start);
            return;
        }
        if (app == null) {
            reject(response, 401, ErrorCode.UNAUTHORIZED, "应用无效/停用/过期", null, appKey, method, path,
                    clientIp, start);
            return;
        }

        // 4. nonce 防重放: SETNX EX 600（2×时间窗: 未来时间戳请求的重放残留窗收敛, 三视角评审 P2）;
        // 仅合法 app 可写 nonce; Redis 故障 fail-closed
        try {
            Boolean first = redissonBucketSetIfAbsent(appKey.trim(), nonce.trim());
            if (!Boolean.TRUE.equals(first)) {
                reject(response, 401, ErrorCode.UNAUTHORIZED, "nonce 重放", app.getAppId(), appKey, method, path,
                        clientIp, start);
                return;
            }
        } catch (Exception e) {
            log.error("OpenAPI Redis 不可用, fail-closed 拒绝(安全优先): ip={} path={}", clientIp, path, e);
            reject(response, 503, ErrorCode.SYSTEM_ERROR, "服务暂不可用, 请稍后重试", app.getAppId(), appKey, method,
                    path, clientIp, start);
            return;
        }

        // 5. 签名比对（恒定时间）: METHOD\nPATH\ntimestamp\nnonce\nSM3Hex(rawBody)
        String signString = method + "\n" + path + "\n" + ts + "\n" + nonce.trim() + "\n"
                + Sm3.digestHex(wrapper.body());
        String expected = hmacBase64(app.getAppSecret(), signString);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, ErrorCode.UNAUTHORIZED, "签名不符", app.getAppId(), appKey, method,
                    path, clientIp, start);
            return;
        }

        // 6. 授权（过滤器内判定, docs/15 步骤3）: 白名单空=仅 GET 只读接口; 显式白名单按路径精确匹配
        if (!READ_METHODS.contains(method)
                && (app.getAllowedApis().isEmpty() || !app.getAllowedApis().contains(path))) {
            reject(response, 403, ErrorCode.FORBIDDEN, "接口不在应用白名单", app.getAppId(), appKey,
                    method, path, clientIp, start);
            return;
        }

        // 7. QPS 频控（app 维度秒级窗口; 超 rate_limit → 429 复用 1009 口径）
        long count;
        try {
            count = rateLimitStore.increment("openapi:rl:" + app.getAppKey(), 1);
        } catch (Exception e) {
            log.error("OpenAPI 频控 Redis 不可用, fail-closed 拒绝(安全优先): appKey={} path={}",
                    app.getAppKey(), path, e);
            reject(response, 503, ErrorCode.SYSTEM_ERROR, "服务暂不可用, 请稍后重试", app.getAppId(),
                    appKey, method, path, clientIp, start);
            return;
        }
        if (count > app.getRateLimit()) {
            reject(response, 429, ErrorCode.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS.getMsg(),
                    app.getAppId(), appKey, method, path, clientIp, start);
            return;
        }

        // 8. 通过 → 认证令牌 + 透传 OpenAppAuth 供 controller 取数据范围。
        // 响应包装: 业务异常经 GlobalExceptionHandler 是 HTTP 200 + 包络 code,
        // 调用日志必须记包络 code 才能让 scope 越权(403)/会话不存在(1007)等可见（三视角评审 P1）
        SecurityContextHolder.getContext().setAuthentication(new OpenApiAuthenticationToken(app));
        wrapper.setAttribute(OPENAPI_APP_ATTRIBUTE, app);
        org.springframework.web.util.ContentCachingResponseWrapper respWrapper =
                new org.springframework.web.util.ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(wrapper, respWrapper);
        } finally {
            logService.record(app.getAppId(), app.getAppKey(), method, path,
                    request.getQueryString() == null ? "" : request.getQueryString(),
                    envelopeCode(respWrapper), clientIp, (int) (System.currentTimeMillis() - start), "");
            // 缓存 body 必须写回真实 response, 否则响应体丢失
            respWrapper.copyBodyToResponse();
        }
        SecurityContextHolder.clearContext();
    }

    /** 结果码口径: 优先取响应包络 code（业务错误码）, 非 JSON 响应回落 HTTP status */
    private int envelopeCode(org.springframework.web.util.ContentCachingResponseWrapper respWrapper) {
        try {
            byte[] content = respWrapper.getContentAsByteArray();
            if (content.length > 0) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);
                if (node != null && node.has("code") && node.get("code").isInt()) {
                    return node.get("code").asInt();
                }
            }
        } catch (Exception ignore) {
            // 非 JSON 响应（理论不发生, openapi 全 JSON）按 HTTP status
        }
        return respWrapper.getStatus();
    }

    /** 签名计算: hmac-sm3=BC HMac / hmac-sha256=JCE, 输出 Base64 */
    private String hmacBase64(String secret, String signString) {
        try {
            if (ALGORITHM_SHA256.equals(algorithm)) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return Base64.getEncoder().encodeToString(mac.doFinal(
                        signString.getBytes(StandardCharsets.UTF_8)));
            }
            return Sm3.hmacBase64(secret, signString.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** nonce 防重放: SET NX EX 600=2×时间窗（key 带 appKey 前缀防跨应用碰撞） */
    private Boolean redissonBucketSetIfAbsent(String appKey, String nonce) {
        return redisson.getBucket("openapi:nonce:" + appKey + ":" + nonce,
                org.redisson.client.codec.StringCodec.INSTANCE).setIfAbsent("1",
                java.time.Duration.ofSeconds(TIMESTAMP_WINDOW_SECONDS * 2));
    }

    /** 统一拒绝出口: 写 R 包络 + 记 open_api_log(含拒绝原因)（不抛出） */
    private void reject(HttpServletResponse response, int status, ErrorCode code, String msg,
            Long appId, String appKey, String method, String path, String clientIp, long start)
            throws IOException {
        logService.record(appId, appKey == null ? "" : appKey, method, path,
                "", status, clientIp, (int) (System.currentTimeMillis() - start), msg);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, msg)));
    }
}
