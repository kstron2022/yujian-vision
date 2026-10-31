package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.service.SysUserService;
import com.yujian.vision.web.security.LoginUser;
import com.yujian.vision.web.security.OnlineSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * 用户管理（docs/03 §2, admin）
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('sys:user:list')")
public class UserController {

    private static final Pattern PWD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");

    public record CreateUserRequest(
            @jakarta.validation.constraints.NotBlank String username,
            @jakarta.validation.constraints.NotBlank String password,
            String nickname,
            List<Long> roleIds) {
    }

    public record UpdateUserRequest(String nickname, Integer status, List<Long> roleIds) {
    }

    public record ResetPwdRequest(@jakarta.validation.constraints.NotBlank String password) {
    }

    private final SysUserService userService;
    private final OnlineSessionService sessions;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "用户分页列表")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        var p = userService.page(page, count, keyword, status);
        List<Map<String, Object>> list = p.getRecords().stream()
                .map(u -> this.toMap(u, userService.roleIds(u.getId()))).toList();
        return R.ok(Map.of("total", p.getTotal(), "list", list));
    }

    private Map<String, Object> toMap(SysUserEntity u, List<Long> roleIds) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("status", u.getStatus());
        m.put("mustChangePwd", u.getMustChangePwd() != null && u.getMustChangePwd() == 1);
        m.put("lastLogin", u.getLastLogin() == null ? "" : u.getLastLogin().toString());
        m.put("roleIds", roleIds);
        return m;
    }

    @Operation(summary = "新建用户（首登强制改密）")
    @PostMapping
    @PreAuthorize("hasAuthority('sys:user:add')")
    @AuditLog(module = "sys", operation = "新建用户", bizType = "user", bizIdExpr = "#req.username()")
    public R<Void> create(@RequestBody @jakarta.validation.Valid CreateUserRequest req) {
        if (!PWD.matcher(req.password()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码需至少8位且同时包含字母和数字");
        }
        userService.create(req.username(), passwordEncoder.encode(req.password()),
                req.nickname(), req.roleIds());
        return R.ok();
    }

    @Operation(summary = "编辑用户（昵称/状态/角色；停用即时吊销会话）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:user:update')")
    @AuditLog(module = "sys", operation = "编辑用户", bizType = "user", bizIdExpr = "#id")
    public R<Void> update(@PathVariable Long id, @RequestBody UpdateUserRequest req,
            @AuthenticationPrincipal LoginUser user) {
        // 产品守卫: 不能停用自己（否则当前账号被即时吊销, 系统失去可登录的管理入口）
        if (user != null && user.userId() == id && req.status() != null && req.status() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用当前登录账号");
        }
        userService.update(id, req.nickname(), req.status(), req.roleIds());
        if (req.status() != null && req.status() == 0) {
            sessions.revokeAll(id);
        }
        return R.ok();
    }

    @Operation(summary = "删除用户（软删并吊销会话）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:user:delete')")
    @AuditLog(module = "sys", operation = "删除用户", bizType = "user", bizIdExpr = "#id")
    public R<Void> delete(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        // 产品守卫: 不能删除自己（否则当前账号被软删+吊销, 系统失去可登录的管理入口）
        if (user != null && user.userId() == id) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除当前登录账号");
        }
        userService.delete(id);
        sessions.revokeAll(id);
        return R.ok();
    }

    @Operation(summary = "重置密码（目标用户首登强制改密并全量下线）")
    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('sys:user:reset-pwd')")
    @AuditLog(module = "sys", operation = "重置密码", bizType = "user", bizIdExpr = "#id")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody @jakarta.validation.Valid ResetPwdRequest req) {
        if (!PWD.matcher(req.password()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码需至少8位且同时包含字母和数字");
        }
        userService.resetPassword(id, passwordEncoder.encode(req.password()));
        sessions.revokeAll(id);
        return R.ok();
    }
}
