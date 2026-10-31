package com.yujian.vision.web.service;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.system.entity.SysMenuEntity;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.service.LoginLogService;
import com.yujian.vision.service.system.service.SysUserService;
import com.yujian.vision.web.security.JwtService;
import com.yujian.vision.web.security.LoginUser;
import com.yujian.vision.web.security.LoginProtectService;
import com.yujian.vision.web.security.OnlineSessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证编排：登录（防爆破+审计）、登出、改密、在线会话管理（M1-9 A1）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 密码复杂度: ≥8 位且同时含字母与数字（等保身份鉴别基线, docs/10 §1） */
    private static final String PWD_PATTERN = "^(?=.*\\d).{8,}$";

    private final SysUserService sysUserService;
    private final LoginLogService loginLogService;
    private final JwtService jwtService;
    private final OnlineSessionService sessions;
    private final LoginProtectService protect;
    private final PasswordEncoder passwordEncoder;
    private final com.yujian.vision.service.monitor.VisionMetrics metrics;

    public record LoginRequest(String username, String password) {
    }

    public record UserInfo(Long id, String username, String nickname, Integer mustChangePwd) {
    }

    public record MenuNode(Long id, Long parentId, String menuName, Integer menuType, String path,
            String component, String icon, Integer sortOrder, List<MenuNode> children) {
    }

    public record LoginResponse(String token, UserInfo user, List<String> roles, java.util.Set<String> perms,
            List<MenuNode> menus) {
    }

    /** 登录：锁定检查 → 口令校验 → 建会话签发 JWT → 审计落库 */
    public LoginResponse login(LoginRequest req, String clientIp, String userAgent) {
        String username = req.username() == null ? "" : req.username().trim();
        String password = req.password() == null ? "" : req.password();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名和密码不能为空");
        }
        protect.checkLocked(username, clientIp);
        SysUserEntity user = sysUserService.findByUsername(username);
        boolean ok = user != null && Integer.valueOf(1).equals(user.getStatus())
                && passwordEncoder.matches(password, user.getPassword());
        if (!ok) {
            protect.onFail(username, clientIp);
            loginLogService.record(user == null ? null : user.getId(), username, clientIp, userAgent,
                    false, user == null ? "用户不存在"
                            : (Integer.valueOf(1).equals(user.getStatus()) ? "密码错误" : "账号已停用"));
            metrics.increment("vision.login", "result", "fail");
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return doLogin(user, clientIp, userAgent);
    }

    private LoginResponse doLogin(SysUserEntity user, String clientIp, String userAgent) {
        protect.onSuccess(user.getUsername(), clientIp);
        sysUserService.markLogin(user.getId());
        loginLogService.record(user.getId(), user.getUsername(), clientIp, userAgent, true, "登录成功");
        String jti = UUID.randomUUID().toString();
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getNickname(), jti);
        sessions.create(jti, user.getId(), user.getUsername(), user.getNickname(), clientIp);
        List<String> roles = sysUserService.roleCodes(user.getId());
        java.util.Set<String> perms = sysUserService.permCodes(user.getId());
        List<MenuNode> menus = buildMenuTree(sysUserService.visibleMenus(user.getId()));
        UserInfo ui = new UserInfo(user.getId(), user.getUsername(), user.getNickname(),
                user.getMustChangePwd());
        log.info("用户登录成功: user={}, ip={}", user.getUsername(), clientIp);
        metrics.increment("vision.login", "result", "success");
        return new LoginResponse(token, ui, roles, perms, menus);
    }

    /** 登出：吊销当前 token 会话 */
    public void logout(LoginUser user) {
        if (user != null && user.tokenId() != null) {
            sessions.revoke(user.tokenId());
        }
    }

    /** 当前用户信息（页面刷新拉取, 含 roles/perms/menus） */
    public LoginResponse me(LoginUser current) {
        SysUserEntity user = sysUserService.findById(current.userId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在或已删除");
        }
        List<String> roles = sysUserService.roleCodes(user.getId());
        java.util.Set<String> perms = sysUserService.permCodes(user.getId());
        List<MenuNode> menus = buildMenuTree(sysUserService.visibleMenus(user.getId()));
        UserInfo ui = new UserInfo(user.getId(), user.getUsername(), user.getNickname(),
                user.getMustChangePwd());
        return new LoginResponse(null, ui, roles, perms, menus);
    }

    /** 修改自己密码：验旧密 → 复杂度校验 → 落库清强制标记 → 吊销全部会话（含当前） */
    public void changePassword(LoginUser current, String oldPwd, String newPwd) {
        SysUserEntity user = sysUserService.findById(current.userId());
        if (user == null || !passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "原密码错误");
        }
        if (newPwd == null || !newPwd.matches(PWD_PATTERN)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码需至少8位且同时包含字母和数字");
        }
        if (newPwd.equals(user.getUsername())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与用户名相同");
        }
        sysUserService.updatePassword(current.userId(), passwordEncoder.encode(newPwd));
        sessions.revokeAll(current.userId());
        log.info("用户改密并吊销全部会话: user={}", user.getUsername());
    }

    /** 在线会话列表（admin） */
    public List<OnlineSessionService.OnlineUserInfo> online() {
        return sessions.list();
    }

    /** 强制下线（admin） */
    public void kick(String tokenId) {
        sessions.revoke(tokenId);
    }

    /** 登录日志分页（admin, docs/03 §2） */
    public com.baomidou.mybatisplus.core.metadata.IPage<com.yujian.vision.service.system.entity.SysLoginLogEntity> loginLogs(
            int page, int count, String username, Integer result) {
        return loginLogService.page(page, count, username, result);
    }

    /** 平铺菜单 → 树（保持入参 sort_order 排序; 评审修复: 原 TreeMap 按 id 排序丢失 sortOrder） */
    private List<MenuNode> buildMenuTree(List<SysMenuEntity> menus) {
        Map<Long, MenuNode> nodes = new LinkedHashMap<>();
        Map<Long, List<Long>> childIds = new java.util.HashMap<>();
        for (SysMenuEntity m : menus) {
            nodes.put(m.getId(), new MenuNode(m.getId(), m.getParentId(), m.getMenuName(),
                    m.getMenuType(), m.getPath(), m.getComponent(), m.getIcon(),
                    m.getSortOrder(), new ArrayList<>()));
            childIds.computeIfAbsent(m.getParentId(), k -> new ArrayList<>()).add(m.getId());
        }
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode n : nodes.values()) {
            MenuNode parent = nodes.get(n.parentId());
            if (parent != null) {
                parent.children().add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }
}
