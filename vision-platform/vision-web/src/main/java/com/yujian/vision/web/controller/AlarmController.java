package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.alarm.service.AlarmService;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报警中心接口（M3-B4, docs/03 报警中心）。
 * 查询/处理按菜单权限点控制（V21: alarm:view / alarm:handle, admin+operator）。
 */
@Tag(name = "报警中心")
@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @Operation(summary = "报警分页列表（deviceId/keyword/handled/start/end 过滤, alarm_time 倒序）")
    @GetMapping
    @PreAuthorize("hasAuthority('alarm:view')")
    public R<Map<String, Object>> list(@RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer handled,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count) {
        var p = alarmService.page(deviceId, keyword, handled, start, end, page, count);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "报警处理（标记已处理, 记录处理人与时间）")
    @PutMapping("/{id}/handle")
    @PreAuthorize("hasAuthority('alarm:handle')")
    @AuditLog(module = "alarm", operation = "报警处理", bizType = "alarm", bizIdExpr = "#id")
    public R<Void> handle(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        alarmService.handle(id, user.username());
        return R.ok();
    }
}
