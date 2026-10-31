package com.yujian.vision.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 录像片段解析/收敛契约（docs/03 §6: 厂商格式容错 + 窗口裁剪 + 间隙合并） */
class RecordSegmentsTest {

    private static final String CH = "34020000001320000001";

    private static Map<String, String> item(String startTime, String endTime) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DeviceID", CH);
        m.put("RecordID", "record-1");
        m.put("Name", "n1");
        m.put("Type", "time");
        m.put("StartTime", startTime);
        m.put("EndTime", endTime);
        return m;
    }

    private static RecordSegment seg(String channelId, String start, String end) {
        return new RecordSegment(channelId, "r", "n", "time",
                LocalDateTime.parse(start, RecordSegments.API_FMT),
                LocalDateTime.parse(end, RecordSegments.API_FMT));
    }

    @Test
    @DisplayName("parse: 标准空格时间格式")
    void parseSpaceFormat() {
        List<RecordSegment> out = RecordSegments.parse(List.of(item("2024-01-01 08:00:00", "2024-01-01 09:00:00")), CH);
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2024, 1, 1, 8, 0, 0), out.get(0).startTime());
        assertEquals(LocalDateTime.of(2024, 1, 1, 9, 0, 0), out.get(0).endTime());
    }

    @Test
    @DisplayName("parse: 'T' 分隔时间格式")
    void parseIsoFormat() {
        List<RecordSegment> out = RecordSegments.parse(List.of(item("2024-01-01T08:00:00", "2024-01-01T09:00:00")), CH);
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2024, 1, 1, 8, 0, 0), out.get(0).startTime());
    }

    @Test
    @DisplayName("parse: 毫秒尾巴 .0/.SSS 容错")
    void parseMillisTail() {
        List<RecordSegment> out = RecordSegments.parse(
                List.of(item("2024-01-01 08:00:00.0", "2024-01-01T09:00:00.123")), CH);
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2024, 1, 1, 8, 0, 0), out.get(0).startTime());
        assertEquals(LocalDateTime.of(2024, 1, 1, 9, 0, 0), out.get(0).endTime());
    }

    @Test
    @DisplayName("parse: 坏时间/倒置条目跳过, 其余保留")
    void parseBadOrInvertedSkipped() {
        List<Map<String, String>> items = List.of(
                item("not-a-time", "2024-01-01 09:00:00"),
                item("2024-01-01 09:00:00", "2024-01-01 08:00:00"),
                item("2024-01-01 10:00:00", "2024-01-01 11:00:00"));
        List<RecordSegment> out = RecordSegments.parse(items, CH);
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0, 0), out.get(0).startTime());
    }

    @Test
    @DisplayName("parse: DeviceID 缺失回退通道编码")
    void parseFallbackChannel() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("RecordID", "r2");
        m.put("StartTime", "2024-01-01 08:00:00");
        m.put("EndTime", "2024-01-01 09:00:00");
        List<RecordSegment> out = RecordSegments.parse(List.of(m), "fallback-ch");
        assertEquals(1, out.size());
        assertEquals("fallback-ch", out.get(0).channelId());
        assertEquals("r2", out.get(0).recordId());
    }

    @Test
    @DisplayName("parse: null/空输入返回空列表")
    void parseEmptyInput() {
        assertTrue(RecordSegments.parse(null, CH).isEmpty());
        assertTrue(RecordSegments.parse(List.of(), CH).isEmpty());
    }

    @Test
    @DisplayName("normalize: 空输入返回空不可变列表")
    void normalizeEmpty() {
        assertTrue(RecordSegments.normalize(null,
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0)).isEmpty());
        List<RecordSegment> out = RecordSegments.normalize(List.of(),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertTrue(out.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> out.add(seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00")));
    }

    @Test
    @DisplayName("normalize: 无重叠不合并")
    void normalizeNoMergeWhenFarApart() {
        RecordSegment a = seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00");
        RecordSegment b = seg(CH, "2024-01-01 10:00:00", "2024-01-01 11:00:00");
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(a, b)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(2, out.size());
        assertEquals(a.startTime(), out.get(0).startTime());
        assertEquals(b.startTime(), out.get(1).startTime());
    }

    @Test
    @DisplayName("normalize: 间隙恰 3 秒合并（边界）")
    void normalizeMergeAtExactGap() {
        RecordSegment a = seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00");
        RecordSegment b = seg(CH, "2024-01-01 09:00:03", "2024-01-01 10:00:00");
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(a, b)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(1, out.size());
        assertEquals("2024-01-01 10:00:00", out.get(0).endTime().format(RecordSegments.API_FMT));
    }

    @Test
    @DisplayName("normalize: 间隙 4 秒不合并")
    void normalizeNoMergeBeyondGap() {
        RecordSegment a = seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00");
        RecordSegment b = seg(CH, "2024-01-01 09:00:04", "2024-01-01 10:00:00");
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(a, b)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("normalize: 完全重叠合并, end 取较大者/属性取 last")
    void normalizeOverlapMerged() {
        RecordSegment a = seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00");
        RecordSegment b = new RecordSegment(CH, "r-b", "n-b", "alarm",
                LocalDateTime.parse("2024-01-01 08:30:00", RecordSegments.API_FMT),
                LocalDateTime.parse("2024-01-01 10:00:00", RecordSegments.API_FMT));
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(a, b)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(1, out.size());
        assertEquals("r", out.get(0).recordId());
        assertEquals("time", out.get(0).type());
        assertEquals("2024-01-01 10:00:00", out.get(0).endTime().format(RecordSegments.API_FMT));
    }

    @Test
    @DisplayName("normalize: 窗口外段 clamp/丢弃")
    void normalizeClampAndDrop() {
        RecordSegment outside = seg(CH, "2023-12-01 08:00:00", "2023-12-01 09:00:00");
        RecordSegment crossing = seg(CH, "2023-12-31 23:00:00", "2024-01-01 02:00:00");
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(outside, crossing)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(1, out.size());
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), out.get(0).startTime());
        assertEquals(LocalDateTime.of(2024, 1, 1, 2, 0, 0), out.get(0).endTime());
    }

    @Test
    @DisplayName("normalize: 乱序输入输出按 startTime 升序")
    void normalizeSortsInput() {
        RecordSegment b = seg(CH, "2024-01-01 10:00:00", "2024-01-01 11:00:00");
        RecordSegment a = seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00");
        List<RecordSegment> out = RecordSegments.normalize(new ArrayList<>(List.of(b, a)),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(2, out.size());
        assertTrue(out.get(0).startTime().isBefore(out.get(1).startTime()));
        assertFalse(out.get(0).startTime().isAfter(out.get(1).startTime()));
    }

    @Test
    @DisplayName("normalize: 返回不可变列表")
    void normalizeImmutable() {
        List<RecordSegment> out = RecordSegments.normalize(
                new ArrayList<>(List.of(seg(CH, "2024-01-01 08:00:00", "2024-01-01 09:00:00"))),
                LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 2, 0, 0));
        assertEquals(1, out.size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> out.remove(0));
    }
}
