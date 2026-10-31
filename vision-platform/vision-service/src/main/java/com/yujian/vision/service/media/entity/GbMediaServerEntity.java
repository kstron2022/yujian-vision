package com.yujian.vision.service.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_media_server ZLM 流媒体节点实体（docs/02 §2.3）
 */
@Data
@TableName("gb_media_server")
public class GbMediaServerEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String serverId;

    /** 内网/平台可达 IP（管理 API 用） */
    private String hostIp;

    /** 设备可达 IP（点播 SDP c= 行使用, docs/04 §4） */
    private String publicIp;

    private Integer httpPort;

    private Integer httpSslPort;

    private Integer rtspPort;

    private Integer rtmpPort;

    private Integer rtcPort;

    private Integer rtpPortStart;

    private Integer rtpPortEnd;

    private Integer hookEnable;

    /** 播放 URL 签名密钥（M1 明文, AES 存储随安全基线落地） */
    private String signSecret;

    private String zlmVersion;

    /** 0:离线 1:在线（on_server_keepalive 判定） */
    private Integer status;

    private Integer streamCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
