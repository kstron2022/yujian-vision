package com.yujian.vision.gb28181.message;

/**
 * MANSCDP+xml 报文常量（GB/T 28181-2016）
 */
public final class ManScdpConstants {

    private ManScdpConstants() {
    }

    /** MESSAGE 消息体 Content-Type */
    public static final String CONTENT_TYPE = "Application/MANSCDP+xml";

    /** 回放控制 INFO 消息体 Content-Type（MANSRTSP XML 形式）。
     *  GB/T 28181-2016 对 MANSRTSP 同时定义了 RTSP 明文形式（docs/07 §3, Content-Type: Application/MANSRTSP）
     *  与 XML 包装形式（Control/Cmd=MANSRTSP）, 厂商实现两者皆有; 本平台取 XML 形式, 对应类型名按
     *  MANSCDP 的 "+xml" 惯例书写（见 ManScdpCodec.buildMansrtspControl 注释） */
    public static final String MANSRTSP_CONTENT_TYPE = "Application/MANSRTSP+xml";

    /** 报文根类型 */
    public static final String ROOT_QUERY = "Query";
    public static final String ROOT_RESPONSE = "RESPONSE";
    public static final String ROOT_NOTIFY = "Notify";
    public static final String ROOT_CONTROL = "Control";

    /** 命令类型 CmdType */
    public static final String CMD_KEEPALIVE = "Keepalive";
    public static final String CMD_CATALOG = "Catalog";
    public static final String CMD_RECORD_INFO = "RecordInfo";
    public static final String CMD_PRESET_QUERY = "PresetQuery";
    public static final String CMD_DEVICE_CONTROL = "DeviceControl";
    public static final String CMD_MOBILE_POSITION = "MobilePosition";
    public static final String CMD_ALARM = "Alarm";

    /** 通道状态 */
    public static final String STATUS_ON = "ON";
    public static final String STATUS_OFF = "OFF";
}
