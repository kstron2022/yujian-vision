package com.yujian.vision.web.openapi;

import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 开放接口认证令牌（docs/15 步骤3）：签名过滤器验签通过后构造,
 * principal=OpenAppAuth 塞 SecurityContext（满足 authenticated(), 开放接口不走 JWT 体系）。
 * authorities=API 白名单映射（空白名单 → OPENAPI_READONLY 仅只读标识）。
 */
public class OpenApiAuthenticationToken extends AbstractAuthenticationToken {

    private final transient OpenAppAuth app;

    public OpenApiAuthenticationToken(OpenAppAuth app) {
        super(buildAuthorities(app));
        this.app = app;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> buildAuthorities(OpenAppAuth app) {
        if (app.getAllowedApis().isEmpty()) {
            return List.of(new SimpleGrantedAuthority("OPENAPI_READONLY"));
        }
        return app.getAllowedApis().stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority("OPENAPI:" + p))
                .toList();
    }

    public OpenAppAuth app() {
        return app;
    }

    @Override
    public Object getPrincipal() {
        return app;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
