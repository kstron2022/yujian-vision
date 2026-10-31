package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_user 系统用户实体（docs/02 §2.13）
 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 摘要（docs/10 §1） */
    private String password;

    private String nickname;

    /** 1启用 0停用 */
    private Integer status;

    /** 1=需强制改密（首登/被重置后） */
    private Integer mustChangePwd;

    private LocalDateTime lastLogin;
    private LocalDateTime deletedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
