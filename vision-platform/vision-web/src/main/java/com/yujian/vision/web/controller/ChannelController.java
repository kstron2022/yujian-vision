package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.device.service.ChannelQueryService;
import com.yujian.vision.web.security.LoginUser;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通道接口（docs/03 §3, M1-9 A4）。GET 登录即可（按数据权限过滤, B3 P0-2）；改名需 channel:update。
 */
@Tag(name = "通道管理")
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelQueryService channelQueryService;
    private final ChannelDataScopeService dataScope;

    @Operation(summary = "通道分页列表（deviceId/query/status/ptz 过滤, 按数据权限收口）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer ptz,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @AuthenticationPrincipal LoginUser user) {
        var scope = dataScope.resolve(user == null ? null : user.userId());
        var p = channelQueryService.page(deviceId, query, status, ptz, page, count, scope);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "通道树（按 parentId 组织, 按数据权限过滤）")
    @GetMapping("/tree")
    public R<List<ChannelQueryService.TreeNode>> tree(@RequestParam(required = false) Long deviceId,
            @AuthenticationPrincipal LoginUser user) {
        var scope = dataScope.resolve(user == null ? null : user.userId());
        return R.ok(channelQueryService.tree(deviceId, scope));
    }

    /**
     * 通道信息 patch（B3"强制云台"开关挂账）: customName/ptzType 传 null 不改;
     * ptzEnabled 传 null 恢复自动（列置 NULL）, 0/1 显式设置;
     * ptzType 0~5 显式设置（M5-B2: LiveGBS 类平台目录不报 PTZType, 球机类型靠人工补录出摇杆）。
     */
    public record ChannelUpdateRequest(String customName, Integer ptzEnabled, Integer ptzType) {
    }

    @Operation(summary = "修改通道（自定义名称 + 云台开关/类型 patch）")
    @PutMapping("/{channelDbId}")
    @PreAuthorize("hasAuthority('channel:update')")
    @AuditLog(module = "device", operation = "通道修改", bizType = "channel", bizIdExpr = "#channelDbId")
    public R<Void> update(@PathVariable Long channelDbId,
            @RequestBody ChannelUpdateRequest req) {
        channelQueryService.updateChannel(channelDbId, req.customName(), req.ptzEnabled(), req.ptzType());
        return R.ok();
    }
}
