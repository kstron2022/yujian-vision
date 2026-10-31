package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.service.system.service.OperLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询（docs/03 §2 契约缺口已补, admin+operator 查看）
 */
@Tag(name = "操作日志")
@RestController
@RequestMapping("/api/oper-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('log:oper:list')")
public class OperLogController {

    private final OperLogService operLogService;

    @Operation(summary = "操作日志分页（module/username/bizType/bizId/result 过滤）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) Integer result) {
        var p = operLogService.page(page, count, module, username, bizType, bizId, result);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }
}
