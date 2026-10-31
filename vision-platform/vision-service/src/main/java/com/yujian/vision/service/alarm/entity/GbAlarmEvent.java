package com.yujian.vision.service.alarm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * gb_alarm_event 报警事件实体（V1__init §gb_alarm_event, M3-B4）：
 * 设备/下级平台报警（MESSAGE Notify|Response/Alarm 与 SIP NOTIFY/Alarm）归一化入库。
 * tenant_id 与 gb_device_channel 同策略：DDL 默认 0, 实体不映射（docs/02 §2.2 风格）。
 * handle_user 存处理人用户名（V21 已将列调整为 VARCHAR(64)）。
 */
@Data
@TableName("gb_alarm_event")
public class GbAlarmEvent {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属设备档案 id（gb_device.id） */
    private Long deviceDbId;

    /** 报警源为通道时的通道档案 id（gb_device_channel.id, 报警源=设备本身时为 null） */
    private Long channelDbId;

    /** 设备国标编码（档案 deviceId） */
    private String deviceId;

    /** 通道国标编码（报警源=通道时, 否则 null） */
    private String channelId;

    /** AlarmMethod 报警方式: 1电话 2短信 3视频 4设备联网 5图片 6人工 */
    private Integer alarmMethod;

    /** AlarmType 报警依据类型（国标类型表, 字典 alarm_type） */
    private Integer alarmType;

    /** AlarmPriority 报警级别: 1一级(最高)~4四级(最低) */
    private Integer alarmPriority;

    /** AlarmTime 报警时间（缺省/非法时取入库时刻） */
    private LocalDateTime alarmTime;

    /** AlarmDescription/Information 报警描述（≤512 字符截断） */
    private String description;

    /** 报警图片路径（设备/上级附带） */
    private String snapPath;

    private BigDecimal longitude;

    private BigDecimal latitude;

    /** 处理状态 0:未处理 1:已处理 */
    private Integer handled;

    /** 处理时间 */
    private LocalDateTime handleTime;

    /** 处理人用户名 */
    private String handleUser;

    private LocalDateTime createTime;
}
