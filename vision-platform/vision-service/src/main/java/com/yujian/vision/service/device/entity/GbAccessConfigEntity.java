package com.yujian.vision.service.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_access_config 国标接入配置（单行, docs/08 §3.1）：
 * 仅存运行时可改项（自动建档默认接入密码）；serverId/domain/SIP 端口保持配置文件管理。
 */
@Data
@TableName("gb_access_config")
public class GbAccessConfigEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 允许自动注册 0:白名单(仅预建档可注册) 1:开放（V18, 默认白名单） */
    private Integer allowAutoRegister;

    /** 自动建档默认接入密码（AES 密文, NULL=用 vision.gb.default-device-password） */
    @JsonIgnore
    private String defaultPassword;

    private LocalDateTime updateTime;
}
