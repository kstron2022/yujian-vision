package com.yujian.vision.service.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_cloud_record 云端录像实体（V1 已建表, docs/04 §1, docs/09 M2 #3）。
 * <p>filePath 是 ZLM 落盘节点的服务器绝对路径, 只在服务端内部使用（下载/清理）,
 * 不允许出 API: 字段级 {@code @JsonIgnore} + page 查询显式排除列, 双保险
 * （下载一律走专用端点 + 一次性 token, 见 CloudRecordService）。</p>
 */
@Data
@TableName("gb_cloud_record")
public class GbCloudRecordEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 落盘节点（gb_media_server.server_id） */
    private String serverId;

    private String app;

    /** ZLM 流 id */
    private String streamId;

    /** 国标设备编码（冗余便于查询） */
    private String deviceId;

    /** 通道 id（归因主键, on_record_mp4 按活跃会话反查） */
    private Long channelDbId;

    /** 国标通道编码（冗余） */
    private String channelId;

    /** 文件绝对路径（服务端内部使用, 不出 API） */
    @JsonIgnore
    private String filePath;

    private String fileName;

    /** 字节 */
    private Long fileSize;

    /** 秒 */
    private Integer duration;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0:本地 1:已上传对象存储（MinIO 可选, M2 未启用） */
    private Integer uploadState;

    private LocalDateTime createTime;
}
