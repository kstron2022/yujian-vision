package com.yujian.vision.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（A3, docs/10 §1）：标注在 Controller 方法上，由 AuditAspect 异步落 sys_oper_log。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 模块: device/play/playback/ptz/cascade/sys */
    String module();

    /** 操作名: 开始点播 / 停止点播 / 云台控制 ... */
    String operation();

    /** 业务对象类型: device/channel/group/cascade/user */
    String bizType() default "";

    /** 业务对象id取值 SpEL（对方法参数求值, 如 "#req.channelDbId()" / "#streamId"） */
    String bizIdExpr() default "";
}
