package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 录像回放接口（docs/03 §6）。
 * 权限矩阵: 回放 = record:play（admin/operator/viewer, V11 迁移已授权）。
 * 编排逻辑复用 MediaSessionService（SEEK 后需重登记 callIndex 才能补 ACK, 故不放独立 PlaybackService）。
 * B3: start/control 拦通道级数据权限（docs/09 M2 #5）, stop 按 D14 决议不拦（登录即可停任意会话）。
 * B3 阶段3: 级联通道(deviceType=2)回放需 cascade:play（service 层统一拦截）, 收流模式恒取设备档案。
 */
@Tag(name = "录像回放")
@RestController
@RequestMapping("/api/playback")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('record:play')")
public class PlaybackController {

    private static final DateTimeFormatter SPACE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MediaSessionService sessionService;
    private final ChannelDataScopeService dataScopeService;

    public record StartRequest(Long channelDbId, Integer tcpMode, String startTime, String endTime) {
    }

    public record ControlRequest(String streamId, String cmd, BigDecimal scale, String seekTime) {
    }

    public record StopRequest(String streamId) {
    }

    @Operation(summary = "开始回放（docs/03 §6）")
    @PostMapping("/start")
    @RateLimit(name = "playback", limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "playback", operation = "开始回放", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Map<String, Object>> start(@RequestBody StartRequest req, @AuthenticationPrincipal LoginUser user) {
        // 通道级数据权限: 越权通道直接 403
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        LocalDateTime start = parseTime(req.startTime());
        LocalDateTime end = parseTime(req.endTime());
        MediaSessionService.PlayResult r = sessionService.playPlayback(
                req.channelDbId(), req.tcpMode(), user == null ? null : user.userId(),
                user == null ? null : user.perms(), start, end);
        // ^ M3-B3 阶段3: 透传 perms, 级联通道回放在 MediaSessionService 统一拦截 cascade:play
        //   且收流模式恒取设备档案 mediaTcpMode（与实况点播同策略, docs/08 §3.3）
        return R.ok(Map.of(
                "streamId", r.streamId(),
                "sessionId", r.sessionId(),
                "status", r.status(),
                "urls", r.urls(),
                // B5: 回放响应同口径携带 videoCodec（回放前端暂固定 FLV, 字段先行对齐契约）
                "videoCodec", r.videoCodec() == null ? "" : r.videoCodec(),
                // M4-B3: 回放控制能力定级——"sip"=GB 全控 / "seek"=ONVIF 仅拖动 / ""=缺省按 GB
                "controlMode", r.controlMode() == null ? "" : r.controlMode()));
    }

    @Operation(summary = "回放控制（PAUSE/RESUME/SCALE/SEEK）")
    @PostMapping("/control")
    @RateLimit(name = "playback", limit = 60, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "playback", operation = "回放控制", bizType = "channel", bizIdExpr = "#req.streamId()")
    public R<Void> control(@RequestBody ControlRequest req, @AuthenticationPrincipal LoginUser user) {
        // 凭 streamId 定位所在通道做数据权限; 无活跃会话时放行, 交给业务层抛 1007
        Long channelDbId = sessionService.channelDbIdOfStream(req.streamId());
        if (channelDbId != null) {
            dataScopeService.checkAccess(channelDbId, user == null ? null : user.userId());
        }
        sessionService.playbackControl(req.streamId(), req.cmd(), req.scale(),
                req.seekTime() == null ? null : parseTime(req.seekTime()));
        return R.ok();
    }

    @Operation(summary = "停止回放（viewer-1, 归零关流; 与点播 stop 同一 service 路径）")
    @PostMapping("/stop")
    @AuditLog(module = "playback", operation = "停止回放", bizType = "channel", bizIdExpr = "#req.streamId()")
    public R<Void> stop(@RequestBody StopRequest req, @AuthenticationPrincipal LoginUser user) {
        sessionService.stop(req.streamId(), user == null ? null : user.userId());
        return R.ok();
    }

    /** 入参时间解析: 仅 "yyyy-MM-dd HH:mm:ss", 解析失败 400（docs/03 §6） */
    private static LocalDateTime parseTime(String raw) {
        String v = raw == null ? "" : raw.trim();
        try {
            return LocalDateTime.parse(v, SPACE_FMT);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
