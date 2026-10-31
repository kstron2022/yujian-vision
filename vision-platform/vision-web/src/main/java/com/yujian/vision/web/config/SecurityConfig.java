package com.yujian.vision.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.web.openapi.OpenApiSignatureFilter;
import com.yujian.vision.web.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（M1-9 任务9）：JWT 无状态认证 + @PreAuthorize 接口鉴权（决策 D11）。
 * <p>放行：登录、ZLM hook（自带 secret 校验）、健康/指标（内网监控）、dev 环境 swagger。
 * 其余一律需认证；401/403 以响应包络 JSON 返回（docs/03 §1.1）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
            org.springframework.beans.factory.ObjectProvider<OpenApiSignatureFilter> openApiFilter,
            Environment env, ObjectMapper objectMapper) throws Exception {
        boolean dev = Arrays.asList(env.getActiveProfiles()).contains("dev");
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/login").permitAll()
                            .requestMatchers("/api/hooks/**").permitAll()
                            // 开放接口: 认证由 OpenApiSignatureFilter 负责（docs/15 步骤3）,
                            // enabled=false 时该路径无任何端点, permitAll 无行为影响
                            .requestMatchers("/openapi/**").permitAll()
                            // 云端录像文件下载: 60s 一次性 token 即鉴权凭证（B3, 无登录态）
                            .requestMatchers(org.springframework.http.HttpMethod.GET,
                                    "/api/cloud-records/file").permitAll()
                            // 通道截图图片: 签名 URL 免登录（M3-B7）, 端点内签名复验
                            // （形制同 /api/cloud-records/file）+ 仅 GET——POST 抓图仍走 JWT+@PreAuthorize
                            .requestMatchers(org.springframework.http.HttpMethod.GET,
                                    "/api/channels/*/snapshot").permitAll()
                            // 告警 webhook: Alertmanager 回调, 控制器内做 X-Vision-Alert-Token 校验（B4）
                            .requestMatchers("/api/alerts/webhook").permitAll()
                            // HA 内部管理口: keepalived notify 脚本回调, 控制器内做 X-Vision-Ha-Token 校验（M3-B6 阶段2）
                            .requestMatchers("/api/ha/internal/**").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll();
                    // swagger 仅 dev 放行（prod 收紧, docs/10 管理 API 不出公网）
                    if (dev) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    // 内嵌前端：页面与静态资源免登录（/api、/ws 等后端前缀仍要求认证）
                    boolean webUi = env.getProperty("vision.webui.embedded", Boolean.class,
                            WebUiConfig.indexPresent());
                    if (webUi) {
                        auth.requestMatchers(WebUiConfig::isWebUiRequest).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeJson(res, 401,
                                R.fail(ErrorCode.UNAUTHORIZED), objectMapper))
                        .accessDeniedHandler((req, res, ex) -> writeJson(res, 403,
                                R.fail(ErrorCode.FORBIDDEN), objectMapper)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        // 开放接口签名过滤器仅在 vision.openapi.enabled=true 时存在（D7）; 置于 JWT 过滤器之前
        OpenApiSignatureFilter signatureFilter = openApiFilter.getIfAvailable();
        if (signatureFilter != null) {
            http.addFilterBefore(signatureFilter, JwtAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeJson(HttpServletResponse res, int status, R<?> body,
            ObjectMapper om) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(om.writeValueAsString(body));
    }
}
