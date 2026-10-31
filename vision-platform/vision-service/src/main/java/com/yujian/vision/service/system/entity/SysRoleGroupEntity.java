package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role_group 角色-分组数据授权（data_scope=2 时生效; 子树经 gb_group 祖链展开）
 */
@Data
@TableName("sys_role_group")
public class SysRoleGroupEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long roleId;

    private Long groupId;
}
