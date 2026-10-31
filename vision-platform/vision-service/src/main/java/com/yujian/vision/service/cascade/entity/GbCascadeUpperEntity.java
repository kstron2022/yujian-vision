package com.yujian.vision.service.cascade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上级平台配置（本平台作为下级注册, docs/02 §2.7 / docs/08 §2）。
 */
@Data
@TableName("gb_cascade_upper")
public class GbCascadeUpperEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 上级平台名称 */
    private String name;

    /** 上级平台国标编码 */
    private String upperDeviceId;

    /** 本平台在上级侧的国标编码 */
    private String localDeviceId;

    private String upperIp;

    private Integer upperPort;

    /** 级联密码（AES 密文存储, Digest 计算时解密, 同 gb_device.password） */
    @JsonIgnore
    private String password;

    /** UDP / TCP */
    private String transport;

    /** 注册有效期（秒） */
    private Integer expires;

    /** 心跳周期（秒） */
    private Integer keepaliveInterval;

    /** 目录上报 1:仅共享通道 2:全部通道 */
    private Integer catalogMode;

    /** 目录变化时主动通知上级 */
    private Integer autoPushCatalog;

    /** 上级云台指令透传到本平台设备（B2 中转使用） */
    private Integer ptzForward;

    /** 0 未启用 1 启用 */
    private Integer status;

    /** 上级注册状态（级联 UA 状态机回写） */
    private Integer online;

    private LocalDateTime registerTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
