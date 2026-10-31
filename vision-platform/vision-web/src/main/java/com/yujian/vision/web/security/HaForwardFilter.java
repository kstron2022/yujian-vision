package com.yujian.vision.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.ha.HaProperties;
import com.yujian.vision.service.ha.HaRuntimeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 备台管理面转发（M3-B6 阶段2）：B 常驻接管理面（hooks/REST 经 VIP:8082 常态落 B）,
 * 其中 SIP 耦合的写操作（点播/回放/对讲/云台/设备控制等）在备台本地无栈必失败——
 * 备台身份下把命中白名单（前缀×方法）的请求原样反代到活跃实例（vision.ha.peer-base-url）执行,
 * 方法/头/body 透传, 响应原样回写; 对端不可达（切换窗口）回 503"主节点不可达, 正在切换"。
 * B promote 后本过滤器自动失效（角色翻转, 本地直服）。
 *
 * <p>置于 security 链之后（@Component OncePerRequestFilter 默认 order 最低=最后执行）,
 * 仅已认证请求才会到达; 对端二次鉴权同信任域（双实例共享 JWT secret 与 Redis 会话库, 同一 token 双端有效）。</p>
 */
@Slf4j
@Component
public class HaForwardFilter extends OncePerRequestFilter {

    private final HaProperties props;
    private final HaRuntimeService ha;
    private final ObjectMapper om;
    private final HttpClient client;

    public HaForwardFilter(HaProperties props, HaRuntimeService ha, ObjectMapper om) {
        this.props = props;
        this.ha = ha;
        this.om = om;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // 未启用 / 非备台身份 → 不拦截（活跃实例本地直服）
        if (!props.isEnabled() || !ha.isStandby()) {
            return true;
        }
        String method = request.getMethod();
        boolean methodHit = props.getForwardMethods().stream().anyMatch(method::equalsIgnoreCase);
        if (!methodHit) {
            return true;
        }
        String uri = request.getRequestURI();
        List<String> prefixes = props.getForwardPrefixes();
        return prefixes == null || prefixes.isEmpty() || prefixes.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws IOException, ServletException {
        String qs = request.getQueryString();
        String target = props.getPeerBaseUrl() + request.getRequestURI() + (qs != null ? "?" + qs : "");
        try {
            byte[] body = request.getInputStream().readAllBytes();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(props.getForwardTimeoutMs()))
                    .method(request.getMethod(),
                            body.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(body)
                                    : HttpRequest.BodyPublishers.noBody());
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")
                        || name.equalsIgnoreCase("transfer-encoding") || name.equalsIgnoreCase("connection")) {
                    continue;
                }
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    rb.header(name, values.nextElement());
                }
            }
            rb.header("X-Vision-Forwarded-By", "standby");
            HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(resp.statusCode());
            resp.headers().firstValue("content-type").ifPresent(response::setContentType);
            if (resp.body() != null && resp.body().length > 0) {
                response.getOutputStream().write(resp.body());
            }
        } catch (Exception e) {
            log.warn("HA 备台转发失败({} {} -> {}): {}", request.getMethod(), request.getRequestURI(),
                    props.getPeerBaseUrl(), e.getMessage());
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(om.writeValueAsString(
                    R.fail(ErrorCode.SYSTEM_ERROR, "主节点不可达, 正在切换, 请稍后重试")));
        }
    }
}
