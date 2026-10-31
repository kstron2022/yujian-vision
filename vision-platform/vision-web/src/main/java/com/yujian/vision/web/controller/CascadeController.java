package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.service.CascadeConfigService;
import com.yujian.vision.service.cascade.service.CascadeCatalogService;
import com.yujian.vision.service.cascade.service.CascadeUpperManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
 * 级联管理接口（docs/03 §9, M3-B1）：
 * 上级平台配置 CRUD/启停/手动推送目录/共享通道绑定；GET 登录即可, 变更需 cascade:config。
 */
@Tag(name = "级联管理")
@RestController
@RequestMapping("/api/cascade")
@RequiredArgsConstructor
public class CascadeController {

    private final CascadeConfigService configService;
    private final CascadeUpperManager upperManager;
    private final CascadeCatalogService catalogService;

    public record SharedChannelBinding(Long channelDbId, String virtualDeviceId) {
    }

    public record SharedChannelReplaceRequest(List<SharedChannelBinding> bindings) {
    }

    @Operation(summary = "上级平台分页列表")
    @GetMapping("/uppers")
    public R<Map<String, Object>> list(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int count) {
        var p = configService.page(keyword, page, count);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords(), "sessions", upperManager.snapshot()));
    }

    @Operation(summary = "上级平台详情")
    @GetMapping("/uppers/{id}")
    public R<GbCascadeUpperEntity> detail(@PathVariable Long id) {
        return R.ok(configService.detail(id));
    }

    @Operation(summary = "新增上级平台配置")
    @PostMapping("/uppers")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "上级配置新增", bizType = "cascade", bizIdExpr = "#req.upperDeviceId()")
    public R<GbCascadeUpperEntity> create(@RequestBody CascadeConfigService.UpperRequest req) {
        return R.ok(configService.create(req));
    }

    @Operation(summary = "修改上级平台配置（password 留空=不修改）")
    @PutMapping("/uppers/{id}")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "上级配置修改", bizType = "cascade", bizIdExpr = "#id")
    public R<GbCascadeUpperEntity> update(@PathVariable Long id, @RequestBody CascadeConfigService.UpperRequest req) {
        return R.ok(configService.update(id, req));
    }

    @Operation(summary = "删除上级平台配置")
    @DeleteMapping("/uppers/{id}")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "上级配置删除", bizType = "cascade", bizIdExpr = "#id")
    public R<Void> remove(@PathVariable Long id) {
        configService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启停注册（enabled=1/0）")
    @PostMapping("/uppers/{id}/enable")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "上级启停", bizType = "cascade", bizIdExpr = "#id")
    public R<Void> enable(@PathVariable Long id, @RequestParam int enabled) {
        configService.enable(id, enabled);
        return R.ok();
    }

    @Operation(summary = "手动向级联上级推送目录")
    @PostMapping("/uppers/{id}/catalog/push")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "目录推送", bizType = "cascade", bizIdExpr = "#id")
    public R<Void> pushCatalog(@PathVariable Long id) {
        catalogService.pushCatalog(id);
        return R.ok();
    }

    @Operation(summary = "共享通道绑定列表")
    @GetMapping("/uppers/{id}/channels")
    public R<List<Map<String, Object>>> sharedChannels(@PathVariable Long id) {
        return R.ok(configService.listShared(id));
    }

    @Operation(summary = "整体替换共享通道绑定")
    @PostMapping("/uppers/{id}/channels")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "共享通道替换", bizType = "cascade", bizIdExpr = "#id")
    public R<Integer> replaceShared(@PathVariable Long id, @RequestBody SharedChannelReplaceRequest req) {
        List<GbCascadeSharedChannelEntity> bindings = new ArrayList<>();
        if (req != null && req.bindings() != null) {
            for (SharedChannelBinding b : req.bindings()) {
                GbCascadeSharedChannelEntity e = new GbCascadeSharedChannelEntity();
                e.setChannelDbId(b.channelDbId());
                e.setVirtualDeviceId(b.virtualDeviceId());
                bindings.add(e);
            }
        }
        return R.ok(configService.replaceShared(id, bindings));
    }

    @Operation(summary = "删除共享通道绑定（channelDbId 缺省=清空）")
    @DeleteMapping("/uppers/{id}/channels")
    @PreAuthorize("hasAuthority('cascade:config')")
    @AuditLog(module = "cascade", operation = "共享通道删除", bizType = "cascade", bizIdExpr = "#id")
    public R<Void> removeShared(@PathVariable Long id,
            @RequestParam(required = false) Long channelDbId) {
        configService.removeShared(id, channelDbId);
        return R.ok();
    }
}
