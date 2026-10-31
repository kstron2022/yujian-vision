package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.record.RecordQueryService;
import com.yujian.vision.service.record.RecordSegment;
import com.yujian.vision.service.record.RecordSegments;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 录像检索接口（docs/03 §6）。
 * 权限矩阵：录像检索 = record:query（admin/operator/viewer, V9 迁移已授权）
 */
@Tag(name = "录像检索")
@RestController
@RequestMapping("/api/record")
@RequiredArgsConstructor
public class RecordController {

    private static final DateTimeFormatter SPACE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RecordQueryService recordQueryService;
    private final ChannelDataScopeService dataScopeService;

    @Operation(summary = "录像检索（按通道+时间窗）")
    @GetMapping("/query")
    @PreAuthorize("hasAuthority('record:query')")
    public R<Map<String, Object>> query(@RequestParam Long channelDbId,
            @RequestParam String start, @RequestParam String end,
            @AuthenticationPrincipal LoginUser user) {
        // 通道级数据权限（docs/09 M2 #5）: 越权通道直接 403
        dataScopeService.checkAccess(channelDbId, user == null ? null : user.userId());
        LocalDateTime s = parseTime(start);
        LocalDateTime e = parseTime(end);
        List<RecordSegment> segments = recordQueryService.query(channelDbId, s, e);
        List<Map<String, Object>> rows = new ArrayList<>(segments.size());
        for (RecordSegment seg : segments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", seg.channelId());
            row.put("recordId", seg.recordId());
            row.put("name", seg.name());
            row.put("type", seg.type());
            row.put("startTime", seg.startTime().format(RecordSegments.API_FMT));
            row.put("endTime", seg.endTime().format(RecordSegments.API_FMT));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelDbId", channelDbId);
        data.put("start", s.format(RecordSegments.API_FMT));
        data.put("end", e.format(RecordSegments.API_FMT));
        data.put("count", rows.size());
        data.put("segments", rows);
        return R.ok(data);
    }

    /** 入参时间解析: 支持 yyyy-MM-dd HH:mm:ss 与 yyyy-MM-dd'T'HH:mm:ss 两种 */
    private static LocalDateTime parseTime(String raw) {
        String v = raw == null ? "" : raw.trim();
        try {
            return LocalDateTime.parse(v, SPACE_FMT);
        } catch (DateTimeParseException ignored) {
            // 落回 'T' 分隔格式
        }
        try {
            return LocalDateTime.parse(v, ISO_FMT);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
