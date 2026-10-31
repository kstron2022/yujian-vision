package com.yujian.vision.service.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_device 设备/下级平台实体（docs/02 §2.1）
 */
@Data
@TableName("gb_device")
public class GbDeviceEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String name;

    /** 1:设备(IPC/NVR) 2:下级平台 */
    private Integer deviceType;

    /** 2016 / 2022 */
    private String protocolVersion;

    /** 接入协议 GB/ONVIF（M4-B1, docs/13 §4）; ONVIF 行 protocolVersion 不用 */
    private String accessProtocol;

    /** ONVIF 用户名（M4-B1）; GB 行不用 */
    private String onvifUsername;

    /** ONVIF 密码（AES 密文, M4-B1; 口径同 password）。不出 API 响应，修改走 PUT 请求体 */
    @JsonIgnore
    private String onvifPassword;

    /** RTSP 拉流地址（AES 密文, M5-B1 docs/14 §4; rtsp://user:pass@host 整串加密, 口径同 password）。
     * 不出 API 响应，修改走 PUT 请求体; 仅 access_protocol=RTSP 行使用 */
    @JsonIgnore
    private String sourceUrl;

    /** RTSP 拉流传输 tcp/udp（M5-B1; 透传 ZLM addStreamProxy rtp_type, 默认 TCP 抗丢包） */
    private String rtspTransport;

    /** 常驻拉流 0=按需(默认) 1=常驻（M5-B1; 启动拉起+断线重建, 会话清理豁免） */
    private Integer alwaysOn;

    private String manufacturer;

    private String model;

    private String firmware;

    private String transport;

    /** 接入密码（M1 明文存储，M1-6 安全基线落地 AES 加密, docs/02 §0）。不出 API 响应，修改走 PUT 请求体 */
    @JsonIgnore
    private String password;

    private Integer expires;

    private Integer heartbeatInterval;

    private Integer heartbeatTimeout;

    /** 1:TCP被动 0:UDP */
    private Integer mediaTcpMode;

    private String ip;

    private Integer port;

    private String hostIp;

    private Integer online;

    private Integer timeDiffMs;

    private LocalDateTime registerTime;

    private LocalDateTime keepaliveTime;

    private LocalDateTime offlineTime;

    private LocalDateTime deletedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 通道数（非库字段: 设备分页批量填充, 列表展示/多通道设备≈NVR 识别辅助） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer channelCount;
}
