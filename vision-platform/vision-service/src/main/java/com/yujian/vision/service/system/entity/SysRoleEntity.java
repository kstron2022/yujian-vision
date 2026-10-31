package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_role 角色实体（决策 D11）
 */
@Data
@TableName("sys_role")
public class SysRoleEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String roleCode;
    private String roleName;
    private String remark;
    /** 数据范围: 1=全部通道 2=按分组/通道授权（docs/09 M2 #5, V12） */
    private Integer dataScope;
    private LocalDateTime createTime;
}
