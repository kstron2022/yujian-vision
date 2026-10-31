package com.yujian.vision.service.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_record_plan 录像计划实体（V1 已建表, docs/09 M2 #3）。
 * week_plan 为 7x48 半小时布尔矩阵 JSON（0=周一..6=周日, slot=hour*2+minute/30）,
 * 结构校验与判定逻辑见 RecordPlanService。
 */
@Data
@TableName("gb_record_plan")
public class GbRecordPlanEntity {

    /** 计划类型: 全局默认（唯一一行） */
    public static final int TYPE_GLOBAL = 1;
    /** 计划类型: 通道级（按 channel_db_id 唯一） */
    public static final int TYPE_CHANNEL = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 计划名（列 NOT NULL, 全局默认/通道计划均需赋值） */
    private String name;

    /** 1:全局默认 2:通道 */
    private Integer planType;

    /** plan_type=2 时必填 */
    private Long channelDbId;

    /** 7x48 半小时布尔矩阵 JSON（周一~周日） */
    private String weekPlan;

    private Integer enable;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
