package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * sys_role_channel 角色-通道直选授权（B3 预留: B3 授权粒度=分组, 直选通道粒度留待后续批次）
 */
@Data
@TableName("sys_role_channel")
public class SysRoleChannelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long roleId;

    private Long channelDbId;
}
