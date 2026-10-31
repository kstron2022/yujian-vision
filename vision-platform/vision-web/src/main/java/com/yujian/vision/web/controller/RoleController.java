package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.system.entity.SysRoleEntity;
import com.yujian.vision.service.system.service.SysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 角色管理与授权（docs/03 §2, admin; 保存角色时提交授权 menuId 集合）
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('sys:role:list')")
public class RoleController {

    public record RoleRequest(String roleCode, String roleName, String remark, List<Long> menuIds) {
    }

    private final SysRoleService roleService;

    @Operation(summary = "角色分页")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(required = false) String keyword) {
        var p = roleService.page(page, count, keyword);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "角色全量列表（用户管理分配角色用）")
    @GetMapping("/all")
    public R<List<SysRoleEntity>> all() {
        return R.ok(roleService.listAll());
    }

    @Operation(summary = "新建角色（含授权）")
    @PostMapping
    @PreAuthorize("hasAuthority('sys:role:add')")
    public R<Void> create(@RequestBody RoleRequest req) {
        SysRoleEntity role = new SysRoleEntity();
        role.setRoleCode(req.roleCode());
        role.setRoleName(req.roleName());
        role.setRemark(req.remark());
        roleService.create(role, req.menuIds());
        return R.ok();
    }

    @Operation(summary = "编辑角色（menuIds 非空时覆盖授权）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:role:update')")
    public R<Void> update(@PathVariable Long id, @RequestBody RoleRequest req) {
        SysRoleEntity patch = new SysRoleEntity();
        patch.setRoleCode(req.roleCode());
        patch.setRoleName(req.roleName());
        patch.setRemark(req.remark());
        roleService.update(id, patch, req.menuIds());
        return R.ok();
    }

    @Operation(summary = "删除角色")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:role:delete')")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.ok();
    }

    @Operation(summary = "角色已授权 menuId 集合")
    @GetMapping("/{id}/menus")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        roleService.get(id);
        return R.ok(roleService.menuIds(id));
    }

    @Operation(summary = "保存角色授权（全量覆盖）")
    @PutMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('sys:role:auth')")
    public R<Void> grant(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        roleService.grant(id, body.get("menuIds"));
        return R.ok();
    }

    public record DataScopeRequest(Integer dataScope, List<Long> groupIds) {
    }

    @Operation(summary = "数据授权查询（dataScope 1=全部通道 2=按分组; B3 通道级授权）")
    @GetMapping("/{id}/data-scope")
    @PreAuthorize("hasAuthority('sys:role:data-scope')")
    public R<Map<String, Object>> dataScope(@PathVariable Long id) {
        return R.ok(roleService.queryDataScope(id));
    }

    @Operation(summary = "保存数据授权（全量覆盖 groupIds; admin 恒全部不依赖此配置）")
    @PutMapping("/{id}/data-scope")
    @PreAuthorize("hasAuthority('sys:role:data-scope')")
    @AuditLog(module = "sys", operation = "角色数据授权", bizType = "role", bizIdExpr = "#id")
    public R<Void> grantDataScope(@PathVariable Long id, @RequestBody DataScopeRequest req) {
        roleService.grantDataScope(id, req.dataScope(), req.groupIds());
        return R.ok();
    }
}
