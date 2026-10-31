package com.yujian.vision.service.record;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 录像片段解析/收敛（docs/03 §6）：厂商时间格式容错 → 窗口裁剪 → 排序 → 间隙合并去重。
 * 纯静态工具，便于单测。
 */
public final class RecordSegments {

    /** API 输出格式（Controller 统一输出 yyyy-MM-dd HH:mm:ss） */
    public static final DateTimeFormatter API_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter SPACE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 合并间隙阈值：相邻段间隙≤3秒或重叠即合并（厂商切片误差） */
    private static final long MERGE_GAP_SECONDS = 3;

    private RecordSegments() {
    }

    /**
     * RecordInfo 应答条目 → 录像片段（坏数据/倒置条目跳过，不影响其余条目）
     */
    public static List<RecordSegment> parse(List<Map<String, String>> items, String fallbackChannelId) {
        List<RecordSegment> out = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        for (Map<String, String> item : items) {
            if (item == null) {
                continue;
            }
            String channelId = isBlank(item.get("DeviceID")) ? fallbackChannelId : item.get("DeviceID");
            String recordId = isBlank(item.get("RecordID")) ? "" : item.get("RecordID");
            String name = isBlank(item.get("Name")) ? "" : item.get("Name");
            String type = isBlank(item.get("Type")) ? "time" : item.get("Type");
            LocalDateTime startTime = parseTime(item.get("StartTime"));
            LocalDateTime endTime = parseTime(item.get("EndTime"));
            if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
                // 时间缺失/解析失败或起止倒置: 跳过该条
                continue;
            }
            out.add(new RecordSegment(channelId, recordId, name, type, startTime, endTime));
        }
        return out;
    }

    /**
     * 收敛：窗口裁剪 → 排序 → 间隙合并去重，返回不可变列表
     */
    public static List<RecordSegment> normalize(List<RecordSegment> raw, LocalDateTime start, LocalDateTime end) {
        List<RecordSegment> clamped = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        for (RecordSegment s : raw) {
            if (s == null) {
                continue;
            }
            LocalDateTime st = s.startTime().isBefore(start) ? start : s.startTime();
            LocalDateTime et = s.endTime().isAfter(end) ? end : s.endTime();
            if (!st.isBefore(et)) {
                // clamp 后时长≤0（窗口外/瞬时段）: 丢弃
                continue;
            }
            clamped.add(new RecordSegment(s.channelId(), s.recordId(), s.name(), s.type(), st, et));
        }
        clamped.sort(Comparator.comparing(RecordSegment::startTime).thenComparing(RecordSegment::endTime));
        List<RecordSegment> out = new ArrayList<>();
        for (RecordSegment cur : clamped) {
            if (out.isEmpty()) {
                out.add(cur);
                continue;
            }
            RecordSegment last = out.get(out.size() - 1);
            if (!cur.startTime().isAfter(last.endTime().plusSeconds(MERGE_GAP_SECONDS))) {
                // 间隙≤3秒或重叠 → 并入前段（属性取 last, end 取较大者）
                LocalDateTime mergedEnd = cur.endTime().isAfter(last.endTime()) ? cur.endTime() : last.endTime();
                out.set(out.size() - 1, new RecordSegment(last.channelId(), last.recordId(), last.name(), last.type(),
                        last.startTime(), mergedEnd));
            } else {
                out.add(cur);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 厂商时间容错解析：trim + 去毫秒尾巴（.0/.SSS）→ 先空格后 'T' 两种格式
     */
    static LocalDateTime parseTime(String raw) {
        String v = raw == null ? "" : raw.trim();
        // 毫秒尾巴（厂商差异）: ...00:00:00.0 / ...T00:00:00.123
        v = v.replaceAll("\\.\\d+$", "");
        try {
            return LocalDateTime.parse(v, SPACE_FMT);
        } catch (DateTimeParseException e) {
            // 落回 'T' 分隔格式
        }
        try {
            return LocalDateTime.parse(v, ISO_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
