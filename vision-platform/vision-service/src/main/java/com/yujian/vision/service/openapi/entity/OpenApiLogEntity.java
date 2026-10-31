package com.yujian.vision.service.openapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * open_api_log 开放接口调用日志实体（docs/02 §2.19）。
 * 异步落库（OpenApiLogService 单线程 daemon）, createTime 走 DB DEFAULT。
 */
@Data
@TableName("open_api_log")
public class OpenApiLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long appId;
    private String appKey;
    private String method;
    private String path;
    /** 请求参数摘要(截断 1024) */
    private String param;
    /** 结果码: 优先响应包络 code(0=成功, 400/403/1007 等业务码), 非 JSON 响应回落 HTTP status（401/429/503） */
    private Integer resultCode;
    private String clientIp;
    private Integer costMs;
    /** 拒绝原因（401/403/429 时为签名过滤器给出的具体文案; 成功为空）, V28 列 */
    private String remark;
    private LocalDateTime createTime;
}
