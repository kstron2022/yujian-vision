package com.yujian.vision.service.record;

import java.time.LocalDateTime;

/**
 * 录像片段（RecordInfo 收敛后, docs/03 §6）
 */
public record RecordSegment(String channelId, String recordId, String name, String type,
        LocalDateTime startTime, LocalDateTime endTime) {
}
