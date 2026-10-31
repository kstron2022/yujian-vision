package com.yujian.vision.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yujian.vision.service.system.entity.SysRoleMenuEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_role_menu 角色-菜单授权关联 mapper
 */
@Mapper
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenuEntity> {
}
