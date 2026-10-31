package com.yujian.vision.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * sys_oper_log 操作日志实体（docs/10 §1 审计, 留存≥6个月）
 */
@Data
@TableName("sys_oper_log")
public class SysOperLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    /** device/play/playback/ptz/cascade/sys */
    private String module;
    private String operation;
    /** 业务对象类型: device/channel/group/cascade/user */
    private String bizType;
    /** 业务对象id(按对象查历史) */
    private String bizId;
    private String detail;
    private String clientIp;
    private Integer costMs;
    /** 1成功 0失败 */
    private Integer result;
    private LocalDateTime createTime;
}
