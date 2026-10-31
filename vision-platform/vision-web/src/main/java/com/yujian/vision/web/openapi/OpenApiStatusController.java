package com.yujian.vision.web.openapi;

import com.yujian.vision.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口启用状态探测（docs/15 管理页配套, 2026-09-26 运维反馈批）:
 * 有意不做 @ConditionalOnProperty 条件装配——「开放接口」菜单是 DB 静态数据恒显示,
 * 未启用时管理页调本端点感知状态, 显示启用引导而非一堆 404/空表。
 * 权限复用 openapi:app:list（V28 菜单 17）。
 */
@Tag(name = "OpenAPI状态", description = "开放接口功能启用状态(管理面)")
@RestController
@RequestMapping("/api")
public class OpenApiStatusController {

    private final boolean enabled;

    public OpenApiStatusController(@Value("${vision.openapi.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Operation(summary = "开放接口启用状态", description = "enabled=false 时管理页显示启用引导, 管理接口不可用")
    @GetMapping("/openapi-status")
    @PreAuthorize("hasAuthority('openapi:app:list')")
    public R<Map<String, Object>> status() {
        return R.ok(Map.of("enabled", enabled));
    }
}
