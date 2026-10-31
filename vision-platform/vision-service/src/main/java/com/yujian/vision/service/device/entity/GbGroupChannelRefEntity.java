package com.yujian.vision.service.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_group_channel_ref 分组-通道多对多（V1 建表, B3 启用）
 */
@Data
@TableName("gb_group_channel_ref")
public class GbGroupChannelRefEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long groupId;

    private Long channelDbId;

    private LocalDateTime createTime;
}
