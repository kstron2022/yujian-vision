package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.system.entity.SysMenuEntity;
import com.yujian.vision.service.system.entity.SysRoleMenuEntity;
import com.yujian.vision.service.system.entity.SysUserRoleEntity;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.mapper.SysMenuMapper;
import com.yujian.vision.service.system.mapper.SysRoleMapper;
import com.yujian.vision.service.system.mapper.SysRoleMenuMapper;
import com.yujian.vision.service.system.mapper.SysUserMapper;
import com.yujian.vision.service.system.mapper.SysUserRoleMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 系统用户/角色/菜单权限读取服务（M1-9 A1/A2）。
 * <p>
 * 密码编码（BCrypt）由 vision-web 层负责，本服务只存取摘要。
 * </p>
 */
@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    public SysUserService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
            SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper, SysMenuMapper menuMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
    }

    public SysUserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username)
                .isNull(SysUserEntity::getDeletedTime));
    }

    public SysUserEntity findById(Long id) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .isNull(SysUserEntity::getDeletedTime));
    }

    /** 用户角色编码集合（登录返回与过滤链权限装载用） */
    public List<String> roleCodes(Long userId) {
        return roleMapper.selectRoleCodesByUserId(userId);
    }

    public List<Long> roleIds(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getUserId, userId))
                .stream().map(SysUserRoleEntity::getRoleId).toList();
    }

    /** 权限码集合（sys_menu.perm_code, 启用未删非空） */
    public Set<String> permCodes(Long userId) {
        List<Long> rids = roleIds(userId);
        if (rids.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenuEntity>()
                .in(SysRoleMenuEntity::getRoleId, rids))
                .stream().map(SysRoleMenuEntity::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return Collections.emptySet();
        }
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenuEntity>()
                .select(SysMenuEntity::getPermCode)
                .in(SysMenuEntity::getId, menuIds)
                .ne(SysMenuEntity::getPermCode, "")
                .eq(SysMenuEntity::getStatus, 1)
                .isNull(SysMenuEntity::getDeletedTime))
                .stream().map(SysMenuEntity::getPermCode).collect(Collectors.toSet());
    }

    /** 可见菜单（目录/菜单, 去重排序）；按钮不进路由树, 权限走 perms[] */
    public List<SysMenuEntity> visibleMenus(Long userId) {
        List<Long> rids = roleIds(userId);
        if (rids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenuEntity>()
                .in(SysRoleMenuEntity::getRoleId, rids))
                .stream().map(SysRoleMenuEntity::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, SysMenuEntity> unique = new LinkedHashMap<>();
        for (SysMenuEntity m : menuMapper.selectList(new LambdaQueryWrapper<SysMenuEntity>()
                .in(SysMenuEntity::getId, menuIds)
                .in(SysMenuEntity::getMenuType, 1, 2)
                .eq(SysMenuEntity::getVisible, 1)
                .eq(SysMenuEntity::getStatus, 1)
                .isNull(SysMenuEntity::getDeletedTime)
                .orderByAsc(SysMenuEntity::getSortOrder))) {
            unique.putIfAbsent(m.getId(), m);
        }
        return List.copyOf(unique.values());
    }

    /** 改密/重置密码落库（encode 由 web 层完成后传入） */
    public void updatePassword(Long userId, String encodedPassword) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .set(SysUserEntity::getPassword, encodedPassword)
                .set(SysUserEntity::getMustChangePwd, 0));
    }

    /** 登录成功回写 last_login */
    public void markLogin(Long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .set(SysUserEntity::getLastLogin, LocalDateTime.now()));
    }

    /** 置位/清除强制改密标记 */
    public void markMustChangePwd(Long userId, boolean must) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, userId)
                .set(SysUserEntity::getMustChangePwd, must ? 1 : 0));
    }

    /** 用户分页（keyword 匹配用户名/昵称） */
    public IPage<SysUserEntity> page(int page, int count,
            String keyword, Integer status) {
        return userMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<SysUserEntity>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(SysUserEntity::getUsername, keyword)
                                .or().like(SysUserEntity::getNickname, keyword))
                        .eq(status != null, SysUserEntity::getStatus, status)
                        .isNull(SysUserEntity::getDeletedTime)
                        .orderByDesc(SysUserEntity::getId));
    }

    /** 新建用户（encodedPassword 由 web 层 BCrypt 后传入） */
    public SysUserEntity create(String username, String encodedPassword, String nickname,
            List<Long> roleIds) {
        if (!StringUtils.hasText(username)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码不能为空");
        }
        if (findByUsername(username) != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        // 角色必须真实存在（防悬挂引用; sys_user_role 无外键, docs/02 §0）
        if (roleIds != null) {
            for (Long rid : roleIds) {
                if (roleMapper.selectById(rid) == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在: " + rid);
                }
            }
        }
        SysUserEntity user = new SysUserEntity();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setNickname(nickname == null ? "" : nickname);
        user.setStatus(1);
        user.setMustChangePwd(1);
        userMapper.insert(user);
        assignRoles(user.getId(), roleIds);
        return user;
    }

    /** 编辑：昵称/状态/角色 */
    public void update(Long id, String nickname, Integer status, List<Long> roleIds) {
        SysUserEntity user = findById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (status != null) {
            if (status != 0 && status != 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "status 仅允许 0/1");
            }
            user.setStatus(status);
        }
        userMapper.updateById(user);
        if (roleIds != null) {
            assignRoles(id, roleIds);
        }
    }

    /** 软删用户（admin 不可删, 删除后吊销会话由 web 层处理） */
    public void delete(Long id) {
        SysUserEntity user = findById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if ("admin".equals(user.getUsername())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "初始管理员不可删除");
        }
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .isNull(SysUserEntity::getDeletedTime)
                .set(SysUserEntity::getDeletedTime, LocalDateTime.now()));
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getUserId, id));
    }

    /** 重置密码（encodedPassword 由 web 层传入, 目标用户首登强制改密） */
    public void resetPassword(Long id, String encodedPassword) {
        SysUserEntity user = findById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .set(SysUserEntity::getPassword, encodedPassword)
                .set(SysUserEntity::getMustChangePwd, 1));
    }

    /** 分配角色（全量覆盖） */
    public void assignRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>()
                .eq(SysUserRoleEntity::getUserId, userId));
        if (roleIds != null) {
            for (Long rid : roleIds) {
                SysUserRoleEntity ur = new SysUserRoleEntity();
                ur.setUserId(userId);
                ur.setRoleId(rid);
                userRoleMapper.insert(ur);
            }
        }
    }
}
