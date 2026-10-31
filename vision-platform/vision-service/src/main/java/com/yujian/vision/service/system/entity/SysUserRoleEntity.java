package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_user_role 用户-角色关联（纯关联表，无 tenant_id，租户插件已忽略）
 */
@Data
@TableName("sys_user_role")
public class SysUserRoleEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private Long roleId;
}
