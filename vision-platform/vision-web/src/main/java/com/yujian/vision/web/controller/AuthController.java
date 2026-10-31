package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.ClientIpResolver;
import com.yujian.vision.web.security.OnlineSessionService;
import com.yujian.vision.web.security.LoginUser;
import com.yujian.vision.web.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与会话接口（docs/03 §2）
 */
@Tag(name = "认证与会话")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    public record ChangePwdRequest(
            @jakarta.validation.constraints.NotBlank(message = "原密码不能为空") String oldPassword,
            @jakarta.validation.constraints.NotBlank(message = "新密码不能为空") String newPassword) {
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    @RateLimit(name = "login", limit = 60, windowSeconds = 60)
    public R<AuthService.LoginResponse> login(@RequestBody @Valid AuthService.LoginRequest req,
            HttpServletRequest request) {
        // 登录防爆破/日志与业务用同一份可信解析后的客户端 IP（防 XFF 伪造绕过防爆破）
        String clientIp = clientIpResolver.resolve(request);
        return R.ok(authService.login(req, clientIp, request.getHeader("User-Agent")));
    }

    @Operation(summary = "注销（吊销当前 token）")
    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal LoginUser user) {
        authService.logout(user);
        return R.ok();
    }

    @Operation(summary = "当前用户信息（含 roles/perms/menus）")
    @GetMapping("/me")
    public R<AuthService.LoginResponse> me(@AuthenticationPrincipal LoginUser user) {
        return R.ok(authService.me(user));
    }

    @Operation(summary = "修改自己密码（改密后全部会话吊销, 需重新登录）")
    @PutMapping("/password")
    public R<Void> password(@RequestBody @Valid ChangePwdRequest req,
            @AuthenticationPrincipal LoginUser user) {
        authService.changePassword(user, req.oldPassword(), req.newPassword());
        return R.ok();
    }

    @Operation(summary = "在线会话列表（admin）")
    @GetMapping("/online")
    @PreAuthorize("hasAuthority('sys:online:list')")
    public R<java.util.List<OnlineSessionService.OnlineUserInfo>> online() {
        return R.ok(authService.online());
    }

    @Operation(summary = "强制下线（admin）")
    @DeleteMapping("/online/{tokenId}")
    @PreAuthorize("hasAuthority('sys:online:kick')")
    public R<Void> kick(@PathVariable String tokenId) {
        authService.kick(tokenId);
        return R.ok();
    }

    @Operation(summary = "登录日志分页（admin, 留存≥6个月）")
    @GetMapping("/login-logs")
    @PreAuthorize("hasAuthority('log:login:list')")
    public R<Map<String, Object>> loginLogs(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer result) {
        var p = authService.loginLogs(page, count, username, result);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }
}
