package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role_menu 角色-菜单授权关联（纯关联表，无 tenant_id，租户插件已忽略）
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenuEntity {

    @TableId(value = "role_id", type = IdType.INPUT)
    private Long roleId;

    private Long menuId;
}
