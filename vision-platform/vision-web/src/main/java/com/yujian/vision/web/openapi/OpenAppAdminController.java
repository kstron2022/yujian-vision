package com.yujian.vision.web.openapi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.openapi.entity.OpenAppEntity;
import com.yujian.vision.service.openapi.service.OpenApiLogService;
import com.yujian.vision.service.openapi.service.OpenAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 开放接口应用管理（docs/15 步骤5）：/api/openapi-apps CRUD + secret 重置 + 启停 + 调用日志分页。
 * 权限点: openapi:app:list/create/update/reset/delete + openapi:log:list（V28 菜单, 默认仅 admin）。
 * app_secret 明文仅在创建/重置响应中出现一次, 列表/详情恒脱敏。
 * 仅 vision.openapi.enabled=true 时装配（D7: 未启用零行为——端点与管理面 API 404;
 * 「开放接口」菜单是 DB 静态数据恒显示, 点入后接口不可用属预期, docs/16 §3 已声明）。
 */
@Tag(name = "OpenAPI应用管理", description = "第三方接入应用 CRUD（管理面）")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenAppAdminController {

    private final OpenAppService appService;
    private final OpenApiLogService logService;

    /** 创建/编辑请求（expireTime 格式 yyyy-MM-dd HH:mm:ss） */
    public record OpenAppReq(String appName, String allowedApis, String channelScope,
            Integer rateLimit, String expireTime) {
    }

    @Operation(summary = "应用分页")
    @GetMapping("/openapi-apps")
    @PreAuthorize("hasAuthority('openapi:app:list')")
    public R<Map<String, Object>> page(@RequestParam(required = false) String appName,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int count) {
        IPage<OpenAppEntity> p = appService.page(page, count, appName, status);
        return R.ok(java.util.Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "应用详情")
    @GetMapping("/openapi-apps/{id}")
    @PreAuthorize("hasAuthority('openapi:app:list')")
    public R<OpenAppEntity> detail(@PathVariable Long id) {
        return R.ok(appService.get(id));
    }

    @Operation(summary = "新建应用", description = "返回 appKey/appSecret 明文, 仅此一次")
    @PostMapping("/openapi-apps")
    @PreAuthorize("hasAuthority('openapi:app:create')")
    public R<OpenAppService.AppCredential> create(@RequestBody OpenAppReq req) {
        return R.ok(appService.create(req.appName(), req.allowedApis(), req.channelScope(),
                req.rateLimit(), parseTime(req.expireTime())));
    }

    @Operation(summary = "编辑应用", description = "不含 secret/状态")
    @PutMapping("/openapi-apps/{id}")
    @PreAuthorize("hasAuthority('openapi:app:update')")
    public R<Void> update(@PathVariable Long id, @RequestBody OpenAppReq req) {
        appService.update(id, req.appName(), req.allowedApis(), req.channelScope(),
                req.rateLimit(), parseTime(req.expireTime()));
        return R.ok();
    }

    @Operation(summary = "重置 secret", description = "返回新 appSecret 明文, 仅此一次; 旧凭证立即失效")
    @PostMapping("/openapi-apps/{id}/reset-secret")
    @PreAuthorize("hasAuthority('openapi:app:reset')")
    public R<OpenAppService.AppCredential> resetSecret(@PathVariable Long id) {
        return R.ok(appService.resetSecret(id));
    }

    @Operation(summary = "启停")
    @PutMapping("/openapi-apps/{id}/status")
    @PreAuthorize("hasAuthority('openapi:app:update')")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        appService.updateStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除应用")
    @DeleteMapping("/openapi-apps/{id}")
    @PreAuthorize("hasAuthority('openapi:app:delete')")
    public R<Void> delete(@PathVariable Long id) {
        appService.delete(id);
        return R.ok();
    }

    @Operation(summary = "调用日志分页", description = "含 401/403/429 拒绝原因 remark")
    @GetMapping("/openapi-logs")
    @PreAuthorize("hasAuthority('openapi:log:list')")
    public R<Map<String, Object>> logs(@RequestParam(required = false) String appKey,
            @RequestParam(required = false) Integer resultCode,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int count) {
        IPage<com.yujian.vision.service.openapi.entity.OpenApiLogEntity> p =
                logService.page(page, count, appKey, resultCode);
        return R.ok(java.util.Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    private static java.time.LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(raw.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new com.yujian.vision.common.exception.BizException(
                    com.yujian.vision.common.api.ErrorCode.BAD_REQUEST,
                    "有效期格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
