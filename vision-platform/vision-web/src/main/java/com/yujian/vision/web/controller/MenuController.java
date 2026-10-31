package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.service.system.entity.SysMenuEntity;
import com.yujian.vision.service.system.service.SysMenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单与按钮维护（docs/03 §2, admin）
 */
@Tag(name = "菜单管理")
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('sys:menu:list')")
public class MenuController {

    private final SysMenuService menuService;

    @Operation(summary = "全量菜单树（含按钮节点）")
    @GetMapping("/tree")
    public R<List<SysMenuService.TreeNode>> tree() {
        return R.ok(menuService.tree());
    }

    public record MenuRequest(Long parentId, String menuName, Integer menuType, String path,
            String component, String permCode, String icon, Integer sortOrder,
            Integer visible, Integer status) {
    }

    private static SysMenuEntity toEntity(MenuRequest r) {
        SysMenuEntity m = new SysMenuEntity();
        m.setParentId(r.parentId());
        m.setMenuName(r.menuName());
        m.setMenuType(r.menuType());
        m.setPath(r.path());
        m.setComponent(r.component());
        m.setPermCode(r.permCode());
        m.setIcon(r.icon());
        m.setSortOrder(r.sortOrder());
        m.setVisible(r.visible());
        m.setStatus(r.status());
        return m;
    }

    @Operation(summary = "新建菜单/按钮")
    @PostMapping
    @PreAuthorize("hasAuthority('sys:menu:add')")
    public R<Void> create(@RequestBody MenuRequest req) {
        menuService.create(toEntity(req));
        return R.ok();
    }

    @Operation(summary = "编辑菜单/按钮")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:menu:update')")
    public R<Void> update(@PathVariable Long id, @RequestBody MenuRequest req) {
        menuService.update(id, toEntity(req));
        return R.ok();
    }

    @Operation(summary = "删除菜单/按钮（软删）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sys:menu:delete')")
    public R<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return R.ok();
    }
}
