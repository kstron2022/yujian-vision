package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_login_log 登录日志实体（docs/10 §1 防爆破审计）
 */
@Data
@TableName("sys_login_log")
public class SysLoginLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String clientIp;
    private String userAgent;
    /** 1成功 0失败 */
    private Integer result;
    private String msg;
    private LocalDateTime createTime;
}
