package com.yujian.vision.gb28181.message;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * MANSCDP 报文解析结果（宽泛字段模型，容忍厂商字段差异）。
 * 目录条目等复杂结构以原始字段表形式携带，由业务层按需取用（docs/01 §3.3 归一化原则）。
 */
@Data
@Builder
public class ManScdpMessage {

    /** 根元素：Query / RESPONSE / Notify / Control */
    private String rootType;

    /** CmdType */
    private String cmdType;

    /** 事务序号 SN */
    private Long sn;

    /** DeviceID（信令主体编码） */
    private String deviceId;

    /** Status（Keepalive: OK） */
    private String status;

    /** PTZCmd（Control/DeviceControl 的 8 字节云台指令 hex, B2 级联透传） */
    private String ptzCmd;

    /** SumNum（目录/录像总条数） */
    private Integer sumNum;

    /** AlarmMethod（报警方式, 1电话~6人工, M3-B4） */
    private Integer alarmMethod;

    /** AlarmType（报警类型, 国标依据类型表 INT, M3-B4） */
    private Integer alarmType;

    /** AlarmPriority（报警级别, 1一级~4四级, M3-B4） */
    private Integer alarmPriority;

    /** AlarmTime（报警时间, yyyy-MM-ddTHH:mm:ss, M3-B4） */
    private String alarmTime;

    /** AlarmDescription/Information（报警描述, M3-B4） */
    private String alarmDescription;

    /** DeviceList/RecordList 的 Item 字段表（目录同步/录像检索应答） */
    private List<Map<String, String>> items;
}
