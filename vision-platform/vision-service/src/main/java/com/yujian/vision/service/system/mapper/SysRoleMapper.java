package com.yujian.vision.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yujian.vision.service.system.entity.SysRoleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRoleEntity> {

    @Select("SELECT r.role_code FROM sys_user_role ur "
            + "JOIN sys_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    @Select("SELECT r.id, r.role_code, r.data_scope FROM sys_user_role ur "
            + "JOIN sys_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<SysRoleEntity> selectRolesByUserId(@Param("userId") Long userId);
}
