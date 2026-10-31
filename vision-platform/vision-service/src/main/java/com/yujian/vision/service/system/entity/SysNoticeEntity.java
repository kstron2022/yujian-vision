package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_notice 站内通知事件实体（docs/03 §2.2, V9）。
 * tenant_id 由租户插件 SQL 层统一注入, 实体不落字段（决策 D12）
 */
@Data
@TableName("sys_notice")
public class SysNoticeEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件类型: DEVICE_OFFLINE/CATALOG_SYNC_FAIL/ALARM(M3) */
    private String type;

    private String title;

    private String content;

    /** 1=信息 2=重要 */
    private Integer level;

    /** 业务主键, 如设备 deviceId */
    private String bizId;

    private LocalDateTime createTime;
    private LocalDateTime deletedTime;
}
