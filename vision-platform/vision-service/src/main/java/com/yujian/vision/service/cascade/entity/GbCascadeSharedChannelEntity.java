package com.yujian.vision.service.cascade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 级联共享通道（共享给指定上级的通道及虚拟编码映射, docs/02 §2.8）。
 */
@Data
@TableName("gb_cascade_shared_channel")
public class GbCascadeSharedChannelEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** gb_cascade_upper.id */
    private Long upperId;

    /** 本平台通道 id（gb_device_channel.id） */
    private Long channelDbId;

    /** 映射给上级的虚拟编码（空=用原编码） */
    private String virtualDeviceId;

    /** 1 启用 */
    private Integer enabled;

    private LocalDateTime createTime;
}
