package com.yujian.vision.onvif;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ONVIF 时间换算（M4-B3 回放, docs/13 §5.3）:
 * xs:dateTime（UTC "Z" / 带偏移 / 设备本地无时区三种形态）↔ 平台本地 LocalDateTime；
 * 海康系 RTSP 回放 URI 的 starttime/endtime 参数用 mtime 紧凑格式（20260918T171129Z）。
 * 纯静态工具, 单测覆盖。
 */
public final class OnvifTime {

    private static final DateTimeFormatter MTIME_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter XS_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private OnvifTime() {
    }

    /**
     * xs:dateTime → 平台本地 LocalDateTime。
     * 带 Z/偏移（ISO_DATE_TIME 全形态含小数秒）按时刻转系统时区; 无时区视为设备本地时间原样取。
     * 解析失败回 null（调用方跳过该条, 不让单条坏时间毁掉整批结果）。
     */
    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (Exception ignored) {
            // 落回无时区形态
        }
        try {
            return LocalDateTime.parse(v, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 平台本地时间 → xs:dateTime UTC（FindRecordings Scope/TimeRange 用） */
    public static String toXsDateTime(LocalDateTime t) {
        return t == null ? "" : XS_UTC.format(t.atZone(java.time.ZoneId.systemDefault()));
    }

    /** 平台本地时间 → 海康系 RTSP 回放 mtime 参数（20260918T171129Z） */
    public static String toMtime(LocalDateTime t) {
        return t == null ? "" : MTIME_UTC.format(t.atZone(java.time.ZoneId.systemDefault()));
    }
}
