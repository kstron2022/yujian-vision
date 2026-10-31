package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.device.service.GroupManageService;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 自定义分组管理（docs/03 §3.1, docs/09 M2 #5, 决策 D13）。
 * 树/通道树登录即可读（预览页分组视图共用, 通道侧带数据权限过滤）；
 * 写操作按按钮权限点方法级校验（V12 种子: group:add/update/delete/bind-channel）。
 */
@Tag(name = "分组管理")
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupManageService groupService;
    private final ChannelDataScopeService dataScope;

    public record GroupRequest(Long parentId, String groupName, Integer sortOrder) {
    }

    public record BindChannelsRequest(List<Long> channelDbIds) {
    }

    @Operation(summary = "分组树（管理页/角色授权树共用）")
    @GetMapping("/tree")
    public R<List<GroupManageService.GroupNode>> tree() {
        return R.ok(groupService.tree());
    }

    @Operation(summary = "分组+通道树（预览页分组视图, 通道已按数据权限过滤）")
    @GetMapping("/channel-tree")
    public R<List<GroupManageService.GroupChannelNode>> channelTree(
            @AuthenticationPrincipal LoginUser user) {
        return R.ok(groupService.treeWithChannels(
                dataScope.channelFilter(user == null ? null : user.userId())));
    }

    @Operation(summary = "分组内通道列表（按数据权限过滤, 纵深防御）")
    @GetMapping("/{id}/channels")
    @PreAuthorize("hasAuthority('group:list')")
    public R<List<GbDeviceChannelEntity>> channels(@PathVariable Long id,
            @AuthenticationPrincipal LoginUser user) {
        return R.ok(groupService.channels(id,
                dataScope.channelFilter(user == null ? null : user.userId())));
    }

    @Operation(summary = "通道绑定视图（全部可见通道+所属分组; 绑定抽屉数据源, 单分组约束下前端隐藏已绑其他分组的通道）")
    @GetMapping("/{id}/bind-view")
    @PreAuthorize("hasAuthority('group:list')")
    public R<GroupManageService.BindView> bindView(@PathVariable Long id,
            @AuthenticationPrincipal LoginUser user) {
        return R.ok(groupService.bindView(id,
                dataScope.channelFilter(user == null ? null : user.userId())));
    }

    @Operation(summary = "新建分组")
    @PostMapping
    @PreAuthorize("hasAuthority('group:add')")
    @AuditLog(module = "group", operation = "新建分组", bizType = "group")
    public R<Map<String, Object>> create(@RequestBody GroupRequest req) {
        Long id = groupService.create(req.parentId(), req.groupName(), req.sortOrder());
        return R.ok(Map.of("id", id));
    }

    @Operation(summary = "编辑分组（可挪挂载点）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('group:update')")
    @AuditLog(module = "group", operation = "编辑分组", bizType = "group", bizIdExpr = "#id")
    public R<Void> update(@PathVariable Long id, @RequestBody GroupRequest req) {
        groupService.update(id, req.parentId(), req.groupName(), req.sortOrder());
        return R.ok();
    }

    @Operation(summary = "删除分组（有子分组/绑定通道时拒绝）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('group:delete')")
    @AuditLog(module = "group", operation = "删除分组", bizType = "group", bizIdExpr = "#id")
    public R<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return R.ok();
    }

    @Operation(summary = "绑定通道到分组（全量覆盖）")
    @PutMapping("/{id}/channels")
    @PreAuthorize("hasAuthority('group:bind-channel')")
    @AuditLog(module = "group", operation = "分组绑定通道", bizType = "group", bizIdExpr = "#id")
    public R<Void> bindChannels(@PathVariable Long id, @RequestBody BindChannelsRequest req) {
        groupService.bindChannels(id, req.channelDbIds());
        return R.ok();
    }
}
