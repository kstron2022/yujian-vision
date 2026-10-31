package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（部署探活 / 冒烟验证）
 */
@Tag(name = "系统")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "app", "vision-platform",
                "time", LocalDateTime.now().toString()));
    }
}
