package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.system.entity.SysMenuEntity;
import com.yujian.vision.service.system.mapper.SysMenuMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 菜单与按钮维护（M1-9 A2, 决策 D11 菜单-按钮一体化）
 */
@Service
@RequiredArgsConstructor
public class SysMenuService {

    private final SysMenuMapper menuMapper;

    /** 全量菜单树（含按钮节点, 菜单管理用, docs/03 §2） */
    public List<TreeNode> tree() {
        List<SysMenuEntity> all = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenuEntity>()
                        .isNull(SysMenuEntity::getDeletedTime)
                        .orderByAsc(SysMenuEntity::getSortOrder));
        return build(all);
    }

    public record TreeNode(Long id, Long parentId, String menuName, Integer menuType, String path,
            String component, String permCode, String icon, Integer sortOrder, Integer visible,
            Integer status, List<TreeNode> children) {
    }

    private List<TreeNode> build(List<SysMenuEntity> all) {
        java.util.Map<Long, TreeNode> nodes = new java.util.LinkedHashMap<>();
        for (SysMenuEntity m : all) {
            nodes.put(m.getId(), new TreeNode(m.getId(), m.getParentId(), m.getMenuName(),
                    m.getMenuType(), m.getPath(), m.getComponent(), m.getPermCode(), m.getIcon(),
                    m.getSortOrder(), m.getVisible(), m.getStatus(), new java.util.ArrayList<>()));
        }
        List<TreeNode> roots = new java.util.ArrayList<>();
        for (TreeNode n : nodes.values()) {
            TreeNode parent = nodes.get(n.parentId());
            if (parent != null) {
                parent.children().add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }

    public SysMenuEntity create(SysMenuEntity menu) {
        if (!StringUtils.hasText(menu.getMenuName()) || menu.getMenuType() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "菜单名称与类型不能为空");
        }
        menu.setId(null);
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        }
        menuMapper.insert(menu);
        return menu;
    }

    public void update(Long id, SysMenuEntity patch) {
        SysMenuEntity menu = menuMapper.selectOne(new LambdaQueryWrapper<SysMenuEntity>()
                .eq(SysMenuEntity::getId, id)
                .isNull(SysMenuEntity::getDeletedTime));
        if (menu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        if (StringUtils.hasText(patch.getMenuName())) {
            menu.setMenuName(patch.getMenuName());
        }
        if (patch.getParentId() != null) {
            if (patch.getParentId().equals(id)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "父级不能是自身");
            }
            menu.setParentId(patch.getParentId());
        }
        if (patch.getMenuType() != null) {
            menu.setMenuType(patch.getMenuType());
        }
        if (patch.getPath() != null) {
            menu.setPath(patch.getPath());
        }
        if (patch.getComponent() != null) {
            menu.setComponent(patch.getComponent());
        }
        if (patch.getPermCode() != null) {
            menu.setPermCode(patch.getPermCode());
        }
        if (patch.getIcon() != null) {
            menu.setIcon(patch.getIcon());
        }
        if (patch.getSortOrder() != null) {
            menu.setSortOrder(patch.getSortOrder());
        }
        if (patch.getVisible() != null) {
            menu.setVisible(patch.getVisible());
        }
        if (patch.getStatus() != null) {
            menu.setStatus(patch.getStatus());
        }
        menuMapper.updateById(menu);
    }

    /** 软删：有子节点先删子（M1 简化：拒绝删含子节点） */
    public void delete(Long id) {
        long children = menuMapper.selectCount(new LambdaQueryWrapper<SysMenuEntity>()
                .eq(SysMenuEntity::getParentId, id)
                .isNull(SysMenuEntity::getDeletedTime));
        if (children > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在子节点，请先删除子节点");
        }
        menuMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysMenuEntity>()
                .eq(SysMenuEntity::getId, id)
                .isNull(SysMenuEntity::getDeletedTime)
                .set(SysMenuEntity::getDeletedTime, java.time.LocalDateTime.now()));
    }
}
