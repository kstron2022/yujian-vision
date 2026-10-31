package com.yujian.vision.common.api;

import lombok.Getter;

/**
 * 全局错误码（docs/03 §1.4）
 */
@Getter
public enum ErrorCode {

    OK(0, "success"),

    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统错误"),

    DEVICE_OFFLINE(1001, "设备离线"),
    CHANNEL_OFFLINE(1002, "通道离线"),
    INVITE_FAILED(1003, "INVITE超时或失败"),
    SSRC_ALLOC_FAILED(1004, "SSRC分配失败"),
    ZLM_API_FAILED(1005, "流媒体接口调用失败"),
    STREAM_NOT_READY(1006, "流未就绪"),
    SESSION_NOT_FOUND(1007, "会话不存在或已关闭"),
    RECORD_NOT_FOUND(1008, "无录像或设备不支持回放"),
    TOO_MANY_REQUESTS(1009, "请求过于频繁，请稍后再试"),
    RECORD_QUERY_TIMEOUT(1010, "录像查询超时：设备未响应或应答不完整（设备可能不支持录像检索，或未装存储介质如 SD 卡/NVR）"),
    PRESET_QUERY_TIMEOUT(1011, "预置位查询超时：设备未响应（设备可能不支持预置位检索）"),
    TALK_UNAVAILABLE(1012, "对讲不可用：设备不支持 G.711A 音频对讲或不允许对讲"),
    SNAPSHOT_FAILED(1013, "抓图失败"),
    SNAPSHOT_NO_STREAM(1014, "通道当前无流，且未开启空闲抓图"),

    CATALOG_SYNC_RUNNING(2001, "目录同步进行中，请勿重复触发"),

    CASCADE_CONFIG_ERROR(3001, "级联配置错误");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
