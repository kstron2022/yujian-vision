package com.yujian.vision.web.security;

import java.util.Set;

/**
 * 登录用户上下文（SecurityContext principal）
 */
public record LoginUser(
        Long userId,
        String username,
        String nickname,
        Set<String> roles,
        Set<String> perms,
        boolean mustChangePwd,
        String tokenId) {
}
