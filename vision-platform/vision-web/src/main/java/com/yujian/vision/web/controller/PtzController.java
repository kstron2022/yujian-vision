package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.device.service.PresetQueryService;
import com.yujian.vision.service.device.service.PtzControlService;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 云台控制接口（docs/03 §5；权限矩阵: admin/operator）。
 * B3: 新增预置位列表（PresetQuery 信令）+ 全端点 USER 维度限流 + 通道级数据权限拦截（docs/09 M1-8 挂账）。
 */
@Tag(name = "云台控制")
@RestController
@RequestMapping("/api/ptz")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ptz:control')")
public class PtzController {

    private final PtzControlService ptzService;
    private final PresetQueryService presetQueryService;
    private final ChannelDataScopeService dataScopeService;

    public record PtzRequest(Long channelDbId, String command, Integer speed) {
    }

    public record PresetRequest(Long channelDbId, Integer presetNum) {
    }

    @Operation(summary = "云台控制（方向/变倍/聚焦/光圈/停止）")
    @PostMapping("/control")
    @RateLimit(name = "ptz", limit = 600, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "ptz", operation = "云台控制", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Void> control(@RequestBody PtzRequest req, @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        ptzService.control(req.channelDbId(), req.command(), req.speed());
        return R.ok();
    }

    @Operation(summary = "设置预置位")
    @PostMapping("/preset/set")
    @RateLimit(name = "ptz", limit = 600, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "ptz", operation = "设置预置位", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Void> presetSet(@RequestBody PresetRequest req, @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        ptzService.preset(req.channelDbId(), "set", req.presetNum());
        return R.ok();
    }

    @Operation(summary = "调用预置位")
    @PostMapping("/preset/goto")
    @RateLimit(name = "ptz", limit = 600, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "ptz", operation = "调用预置位", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Void> presetGoto(@RequestBody PresetRequest req, @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        ptzService.preset(req.channelDbId(), "goto", req.presetNum());
        return R.ok();
    }

    @Operation(summary = "删除预置位")
    @PostMapping("/preset/delete")
    @RateLimit(name = "ptz", limit = 600, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "ptz", operation = "删除预置位", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Void> presetDelete(@RequestBody PresetRequest req, @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        ptzService.preset(req.channelDbId(), "delete", req.presetNum());
        return R.ok();
    }

    @Operation(summary = "预置位列表（PresetQuery 单包应答, 5s 超时; 空列表=设备无预置位）")
    @GetMapping("/preset/list")
    @RateLimit(name = "ptz", limit = 600, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public R<List<PresetQueryService.PresetItem>> presetList(@RequestParam Long channelDbId,
            @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(channelDbId, user == null ? null : user.userId());
        return R.ok(presetQueryService.query(channelDbId));
    }
}
