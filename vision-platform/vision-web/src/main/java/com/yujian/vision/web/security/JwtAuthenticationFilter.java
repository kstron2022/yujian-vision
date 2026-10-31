package com.yujian.vision.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.service.SysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：Bearer 头 / WS 握手 ?token= → 校验签名、Redis 会话、空闲续期、
 * 装载 roles/perms 进 SecurityContext；must_change_pwd=1 时仅放行改密相关接口。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 强制改密期间仅放行的路径（其余一律 403） */
    private static final List<String> PWD_PENDING_ALLOWED = List.of(
            "/api/auth/logout", "/api/auth/me", "/api/auth/password");

    private final JwtService jwtService;
    private final OnlineSessionService sessions;
    private final SysUserService sysUserService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, OnlineSessionService sessions,
            SysUserService sysUserService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.sysUserService = sysUserService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtService.parse(token);
            String jti = claims.getId();
            Long userId = ((Number) claims.get("uid")).longValue();
            OnlineSessionService.SessionInfo session = sessions.get(jti);
            if (session == null) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "登录状态已失效，请重新登录");
                return;
            }
            if (!sessions.refreshIdle(jti)) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已超时，请重新登录");
                return;
            }
            SysUserEntity user = sysUserService.findById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                sessions.revoke(jti);
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "账号不可用");
                return;
            }

            Set<String> perms = sysUserService.permCodes(userId);
            var authorities = perms.stream().map(SimpleGrantedAuthority::new).toList();
            LoginUser loginUser = new LoginUser(userId, user.getUsername(), user.getNickname(),
                    Set.copyOf(sysUserService.roleCodes(userId)), perms,
                    user.getMustChangePwd() != null && user.getMustChangePwd() == 1, jti);
            if (loginUser.mustChangePwd() && !PWD_PENDING_ALLOWED.contains(request.getRequestURI())) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "请先修改初始密码");
                return;
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "token 无效");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Authorization: Bearer 优先；WS 握手允许 ?token=（docs/03 §10） */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        if (request.getRequestURI().startsWith("/ws/")) {
            return request.getParameter("token");
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(
                status == 401 ? ErrorCode.UNAUTHORIZED : ErrorCode.FORBIDDEN, msg)));
    }
}
