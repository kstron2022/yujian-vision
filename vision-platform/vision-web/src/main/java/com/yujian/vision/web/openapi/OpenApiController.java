package com.yujian.vision.web.openapi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.service.ChannelQueryService;
import com.yujian.vision.service.device.service.DeviceManageService;
import com.yujian.vision.service.device.service.GroupManageService;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.service.OpenApiScopeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口 8 端点（docs/15 步骤4）：认证/频控/白名单授权由 OpenApiSignatureFilter 负责,
 * 本层只做 channel_scope 数据范围过滤与薄适配（复用 Web 端同款 service, 口径一致）。
 * 响应包络 R / 错误码对齐 docs/03 §1。
 */
@Tag(name = "OpenAPI", description = "第三方开放接口（HMAC-SM3 签名认证）")
@RestController
@RequestMapping("/openapi/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenApiController {

    private static final DateTimeFormatter API_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DeviceManageService deviceManageService;
    private final ChannelQueryService channelQueryService;
    private final GroupManageService groupManageService;
    private final MediaSessionService sessionService;
    private final com.yujian.vision.service.record.RecordQueryService recordQueryService;
    private final com.yujian.vision.service.device.service.PtzControlService ptzService;
    private final OpenApiScopeService scopeService;

    private static OpenAppAuth app(HttpServletRequest request) {
        OpenAppAuth auth = (OpenAppAuth) request.getAttribute(OpenApiSignatureFilter.OPENAPI_APP_ATTRIBUTE);
        // 防御性 401（三视角评审 P2）: 签名过滤器与路由的路径判定若将来失配（context-path/
        // 解码型代理）, 未认证请求不得以 NPE→500 或裸数据形态漏出
        if (auth == null) {
            throw new com.yujian.vision.common.exception.BizException(
                    com.yujian.vision.common.api.ErrorCode.UNAUTHORIZED, "缺少开放接口认证上下文");
        }
        return auth;
    }

    // ---------------------------------------------------------------- 只读查询

    @Operation(summary = "设备列表", description = "只读; online 0/1, keyword 匹配设备编码/名称")
    @GetMapping("/devices")
    public R<Map<String, Object>> devices(@RequestParam(required = false) Integer online,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String accessProtocol,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int count) {
        IPage<GbDeviceEntity> p = deviceManageService.page(online, keyword, accessProtocol, page, count);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "通道列表", description = "只读; 强制按应用 channel_scope 过滤（越权防线）")
    @GetMapping("/channels")
    public R<Map<String, Object>> channels(@RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String query, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer ptz,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int count,
            HttpServletRequest request) {
        var p = channelQueryService.page(deviceId, query, status, ptz, page, count,
                scopeService.resolve(app(request)));
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "分组树", description = "只读; 全量分组结构")
    @GetMapping("/groups")
    public R<List<GroupManageService.GroupNode>> groups() {
        return R.ok(groupManageService.tree());
    }

    @Operation(summary = "录像检索", description = "只读; 时间格式 yyyy-MM-dd HH:mm:ss; 强制 channel_scope 校验")
    @GetMapping("/records")
    public R<Map<String, Object>> records(@RequestParam Long channelDbId, @RequestParam String start,
            @RequestParam String end, HttpServletRequest request) {
        scopeService.checkAccess(app(request), channelDbId);
        List<com.yujian.vision.service.record.RecordSegment> segments =
                recordQueryService.query(channelDbId, parseTime(start), parseTime(end));
        List<Map<String, Object>> rows = new ArrayList<>(segments.size());
        for (com.yujian.vision.service.record.RecordSegment seg : segments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelId", seg.channelId());
            row.put("recordId", seg.recordId());
            row.put("name", seg.name());
            row.put("type", seg.type());
            row.put("startTime", seg.startTime().format(API_FMT));
            row.put("endTime", seg.endTime().format(API_FMT));
            rows.add(row);
        }
        return R.ok(Map.of("total", rows.size(), "list", rows));
    }

    // ---------------------------------------------------------------- 实时点播

    public record OpenPlayReq(Long channelDbId, Integer tcpMode, Integer streamProfile) {
    }

    public record OpenStopReq(String streamId) {
    }

    @Operation(summary = "实时点播", description = "写接口(需白名单); 返回带签名的播放 URL 集")
    @PostMapping("/play")
    public R<Map<String, Object>> play(@RequestBody OpenPlayReq req, HttpServletRequest request) {
        scopeService.checkAccess(app(request), req.channelDbId());
        // 对齐 Web 端 doPlay 口径: tcpMode 缺省 1(UDP), streamProfile 缺省 1(主码流); userId/perms=null=内部调用方
        MediaSessionService.PlayResult r = sessionService.play(req.channelDbId(),
                req.tcpMode() == null ? 1 : req.tcpMode(),
                req.streamProfile() == null ? 1 : req.streamProfile(), null, null);
        return R.ok(playView(r));
    }

    @Operation(summary = "停止点播", description = "写接口(需白名单)")
    @PostMapping("/play/stop")
    public R<Void> playStop(@RequestBody OpenStopReq req, HttpServletRequest request) {
        checkStreamAccess(app(request), req.streamId());
        sessionService.stop(req.streamId(), null);
        return R.ok();
    }

    // ---------------------------------------------------------------- 云台

    public record OpenPtzReq(Long channelDbId, String command, Integer speed) {
    }

    // TODO(openapi): 云台控制暂不对外开放（写操作风险需生产安全评估: 物理设备操控 + 当前
    //  缺独立操作审计口径）。恢复时: 还原下方两个注解, 并同步 docs/16 手册接口表 /
    //  sim_openapi.py 写正例 / 前端 OPENAPI_APIS 提示清单。方法体与越权防线保留未删。
    // @Operation(summary = "云台控制", description = "写接口(需白名单); command/speed 口径同 Web 端")
    // @PostMapping("/ptz/control")
    public R<Void> ptzControl(@RequestBody OpenPtzReq req, HttpServletRequest request) {
        scopeService.checkAccess(app(request), req.channelDbId());
        ptzService.control(req.channelDbId(), req.command(), req.speed());
        return R.ok();
    }

    // ---------------------------------------------------------------- 回放

    public record OpenPlaybackStartReq(Long channelDbId, Integer tcpMode,
            String startTime, String endTime) {
    }

    public record OpenPlaybackControlReq(String streamId, String cmd, java.math.BigDecimal scale,
            String seekTime) {
    }

    @Operation(summary = "回放点播", description = "写接口(需白名单); 跨度≤31天; 2h 强制回收上限沿用")
    @PostMapping("/playback/start")
    public R<Map<String, Object>> playbackStart(@RequestBody OpenPlaybackStartReq req,
            HttpServletRequest request) {
        scopeService.checkAccess(app(request), req.channelDbId());
        MediaSessionService.PlayResult r = sessionService.playPlayback(req.channelDbId(),
                req.tcpMode(), null, null, parseTime(req.startTime()), parseTime(req.endTime()));
        Map<String, Object> view = new LinkedHashMap<>(playView(r));
        view.put("controlMode", r.controlMode() == null ? "" : r.controlMode());
        return R.ok(view);
    }

    @Operation(summary = "回放控制", description = "写接口(需白名单); cmd=PAUSE/RESUME/SCALE/SEEK")
    @PostMapping("/playback/control")
    public R<Void> playbackControl(@RequestBody OpenPlaybackControlReq req, HttpServletRequest request) {
        Long channelDbId = sessionService.channelDbIdOfStream(req.streamId());
        if (channelDbId != null) {
            scopeService.checkAccess(app(request), channelDbId);
        }
        sessionService.playbackControl(req.streamId(), req.cmd(), req.scale(),
                req.seekTime() == null ? null : parseTime(req.seekTime()));
        return R.ok();
    }

    @Operation(summary = "停止回放", description = "写接口(需白名单)")
    @PostMapping("/playback/stop")
    public R<Void> playbackStop(@RequestBody OpenStopReq req, HttpServletRequest request) {
        checkStreamAccess(app(request), req.streamId());
        sessionService.stop(req.streamId(), null);
        return R.ok();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 按 streamId 操作的统一越权防线（三视角评审 P0）：实况 streamId = "gb_play_" + 通道国标码
     * 完全可构造, 无 scope 校验时任意 app 可停全平台任意会话。有活跃会话 → 校验通道在应用
     * 数据范围内; 无活跃会话 → 放行交业务层抛 1007（与 playbackControl 同构, 不泄露存在性）。
     * 残余面（评审挂账）: 同 scope 应用可互控对方会话——会话级属主隔离需 gb_media_session
     * 落 app_id, 属独立批次（docs/15 挂账）。
     */
    private void checkStreamAccess(OpenAppAuth auth, String streamId) {
        Long channelDbId = sessionService.channelDbIdOfStream(streamId);
        if (channelDbId != null) {
            scopeService.checkAccess(auth, channelDbId);
        }
    }

    private Map<String, Object> playView(MediaSessionService.PlayResult r) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("streamId", r.streamId());
        view.put("sessionId", r.sessionId());
        view.put("status", r.status());
        view.put("urls", r.urls());
        view.put("videoCodec", r.videoCodec() == null ? "" : r.videoCodec());
        return view;
    }

    /** 时间解析: 仅 "yyyy-MM-dd HH:mm:ss"（口径同 Web 端, 第三方手册统一格式） */
    private static LocalDateTime parseTime(String raw) {
        try {
            return LocalDateTime.parse(raw == null ? "" : raw.trim(), API_FMT);
        } catch (Exception e) {
            throw new com.yujian.vision.common.exception.BizException(
                    com.yujian.vision.common.api.ErrorCode.BAD_REQUEST,
                    "时间格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
