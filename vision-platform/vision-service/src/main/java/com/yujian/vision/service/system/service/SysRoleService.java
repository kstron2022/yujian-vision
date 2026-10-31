package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.GroupManageService;
import com.yujian.vision.service.system.entity.SysRoleChannelEntity;
import com.yujian.vision.service.system.entity.SysRoleEntity;
import com.yujian.vision.service.system.entity.SysRoleGroupEntity;
import com.yujian.vision.service.system.entity.SysRoleMenuEntity;
import com.yujian.vision.service.system.entity.SysUserRoleEntity;
import com.yujian.vision.service.system.mapper.SysRoleChannelMapper;
import com.yujian.vision.service.system.mapper.SysRoleGroupMapper;
import com.yujian.vision.service.system.mapper.SysRoleMapper;
import com.yujian.vision.service.system.mapper.SysRoleMenuMapper;
import com.yujian.vision.service.system.mapper.SysUserRoleMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 角色管理与授权（M1-9 A2, 决策 D11）。admin/operator/viewer 为内置角色不可删。
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    private static final Set<String> BUILTIN = Set.of("admin", "operator", "viewer");

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleGroupMapper roleGroupMapper;
    private final SysRoleChannelMapper roleChannelMapper;
    private final GroupManageService groupService;

    public IPage<SysRoleEntity> page(int page, int count, String keyword) {
        return roleMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<SysRoleEntity>()
                        .like(StringUtils.hasText(keyword), SysRoleEntity::getRoleName, keyword)
                        .orderByAsc(SysRoleEntity::getId));
    }

    public java.util.List<SysRoleEntity> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>()
                .orderByAsc(SysRoleEntity::getId));
    }

    public SysRoleEntity get(Long id) {
        SysRoleEntity role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    public java.util.List<Long> menuIds(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenuEntity>()
                        .eq(SysRoleMenuEntity::getRoleId, roleId))
                .stream().map(SysRoleMenuEntity::getMenuId).toList();
    }

    @Transactional
    public SysRoleEntity create(SysRoleEntity role, List<Long> grantMenuIds) {
        if (!StringUtils.hasText(role.getRoleCode()) || !StringUtils.hasText(role.getRoleName())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色编码与名称不能为空");
        }
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRoleEntity>()
                .eq(SysRoleEntity::getRoleCode, role.getRoleCode())) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色编码已存在");
        }
        roleMapper.insert(role);
        grant(role.getId(), grantMenuIds);
        return role;
    }

    @Transactional
    public void update(Long id, SysRoleEntity patch, List<Long> grantMenuIds) {
        SysRoleEntity role = get(id);
        if (BUILTIN.contains(role.getRoleCode()) && patch.getRoleCode() != null
                && !patch.getRoleCode().equals(role.getRoleCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置角色编码不可修改");
        }
        role.setRoleName(StringUtils.hasText(patch.getRoleName()) ? patch.getRoleName() : role.getRoleName());
        role.setRemark(patch.getRemark());
        roleMapper.updateById(role);
        if (grantMenuIds != null) {
            grant(id, grantMenuIds);
        }
    }

    @Transactional
    public void grant(Long roleId, List<Long> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenuEntity>()
                .eq(SysRoleMenuEntity::getRoleId, roleId));
        if (menuIds != null) {
            for (Long mid : menuIds) {
                SysRoleMenuEntity rm = new SysRoleMenuEntity();
                rm.setRoleId(roleId);
                rm.setMenuId(mid);
                roleMenuMapper.insert(rm);
            }
        }
    }

    /**
     * 数据授权（docs/03 §2, B3）：dataScope 1=全部通道 2=按分组授权; groupIds 全量覆盖。
     * admin 角色数据范围固定按 1 处理（解析时短路, 不依赖该字段）。
     * <p>P0-3 加固: groupIds 每个分组必须存在且未删（不存在→400）; 保存时顺带清空该角色的
     * sys_role_channel 直选行（B3 直选未开放, 防未来放开时残留旧授权）。
     */
    @Transactional
    public void grantDataScope(Long roleId, Integer dataScope, List<Long> groupIds) {
        SysRoleEntity role = get(roleId);
        if (dataScope == null || (dataScope != 1 && dataScope != 2)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "dataScope 只能为 1(全部通道) 或 2(按分组授权)");
        }
        // 逐个校验分组存在且未删（requireGroup 在 device 包, 同一 vision-service 模块跨包注入）
        for (Long gid : new java.util.LinkedHashSet<>(groupIds == null ? List.of() : groupIds)) {
            groupService.requireGroupExists(gid);
        }
        role.setDataScope(dataScope);
        roleMapper.updateById(role);
        roleGroupMapper.delete(new LambdaQueryWrapper<SysRoleGroupEntity>()
                .eq(SysRoleGroupEntity::getRoleId, roleId));
        if (groupIds != null) {
            for (Long gid : new java.util.LinkedHashSet<>(groupIds)) {
                SysRoleGroupEntity rg = new SysRoleGroupEntity();
                rg.setRoleId(roleId);
                rg.setGroupId(gid);
                roleGroupMapper.insert(rg);
            }
        }
        // B3 通道直选未开放: 授权时顺带清空历史直选行, 防未来放开时残留越权数据
        roleChannelMapper.delete(new LambdaQueryWrapper<SysRoleChannelEntity>()
                .eq(SysRoleChannelEntity::getRoleId, roleId));
    }

    /** 数据授权回显（docs/03 §2）：{dataScope, groupIds} */
    public Map<String, Object> queryDataScope(Long roleId) {
        SysRoleEntity role = get(roleId);
        List<Long> groupIds = roleGroupMapper.selectList(new LambdaQueryWrapper<SysRoleGroupEntity>()
                        .eq(SysRoleGroupEntity::getRoleId, roleId))
                .stream().map(SysRoleGroupEntity::getGroupId).toList();
        return Map.of("dataScope", role.getDataScope() == null ? 1 : role.getDataScope(), "groupIds", groupIds);
    }

    @Transactional
    public void delete(Long id) {
        SysRoleEntity role = get(id);
        if (BUILTIN.contains(role.getRoleCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置角色不可删除");
        }
        long users = userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getRoleId, id));
        if (users > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "角色下存在用户，不可删除");
        }
        roleMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenuEntity>()
                .eq(SysRoleMenuEntity::getRoleId, id));
    }
}
