package com.yujian.vision.service.openapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * open_app 第三方接入应用实体（docs/02 §2.19, docs/15 步骤2）。
 * app_secret 为 SM4 密文（"sm4$" 前缀, SmCipher; 表注释"AES加密存储"为建表时旧口径,
 * 由 V28 顺带修正）。tenant_id 由租户插件 SQL 层统一注入, 实体不落字段（决策 D12）。
 */
@Data
@TableName("open_app")
public class OpenAppEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 32 位随机 hex, 第三方身份标识 */
    private String appKey;

    /** SM4 密文, 明文仅在创建/重置时返回一次 */
    private String appSecret;

    private String appName;

    /** API 白名单(逗号分隔路径, 空=仅只读接口) */
    private String allowedApis;

    /** 可见分组 id 集合(逗号分隔, 空=全部; 步骤4 channel_scope 越权防线) */
    private String channelScope;

    /** QPS 限频 */
    private Integer rateLimit;

    /** 接入有效期(null=永久, 过期视同停用) */
    private LocalDateTime expireTime;

    /** 1=启用 0=停用 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
