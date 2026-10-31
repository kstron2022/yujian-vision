package com.yujian.vision.service.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_channel_snapshot 通道最新截图元数据（M3-B7 阶段2, V23）。
 * 图片本体不入库——落盘 {vision.snapshot.dir}/{channelDbId}_{epochMs}.jpg（原图）+ _t.jpg（缩略图）。
 * PK=channel_db_id 每通道只留最新一张（写新删旧）。
 */
@Data
@TableName("gb_channel_snapshot")
public class GbChannelSnapshotEntity {

    /** 来源常量: 点播流就绪自动抓 */
    public static final String SOURCE_AUTO = "AUTO";
    /** 来源常量: 空闲通道抓图（临时建流, 抓完即停） */
    public static final String SOURCE_IDLE = "IDLE";
    /** 来源常量: 手动抓图 */
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(value = "channel_db_id", type = IdType.INPUT)
    private Long channelDbId;

    /** 租户ID（v1 单域固定 0, D12） */
    private Long tenantId;

    /** 原图文件名 {channelDbId}_{epochMs} */
    private String fileName;

    /** 缩略图文件名（缩略生成失败为 null, 列表回退原图） */
    private String thumbName;

    private LocalDateTime captureTime;

    /** 来源 AUTO/IDLE/MANUAL */
    private String source;

    /** 抓图时流所在媒体节点 */
    private String serverId;

    private Integer width;

    private Integer height;

    private Long sizeBytes;
}
