package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_user_message 用户消息已读态实体（docs/03 §2.2, V9）：
 * 一用户一通知一行（扇出行）, read_time 非空即已读。
 * 纯关联表无 tenant_id, 在租户插件白名单（MybatisPlusConfig）
 */
@Data
@TableName("sys_user_message")
public class SysUserMessageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long noticeId;

    private Long userId;

    private LocalDateTime readTime;
    private LocalDateTime createTime;
}
