package com.yujian.vision.onvif.model;

import java.time.LocalDateTime;

/**
 * ONVIF 录像检索片段（M4-B3, docs/13 §5.3）：GetRecordingSearchResults 的
 * RecordingInformation 收敛形态——时间段取 Video track 的 DataFrom/DataTo,
 * 缺 Video track 时回退 Recording 级 Earliest/Latest（解析器内完成）。
 *
 * @param recordingToken 设备侧录像令牌（回放侧 RecordingToken 语义）
 * @param sourceName     录像来源名（展示用, 可空）
 * @param start          片段起（平台本地时区）
 * @param end            片段止（平台本地时区）
 */
public record OnvifRecordingSegment(String recordingToken, String sourceName,
        LocalDateTime start, LocalDateTime end) {
}
