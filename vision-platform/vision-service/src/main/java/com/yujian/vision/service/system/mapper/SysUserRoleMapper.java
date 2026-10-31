package com.yujian.vision.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yujian.vision.service.system.entity.SysUserRoleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user_role 用户-角色关联 mapper
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRoleEntity> {
}
