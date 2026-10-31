package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.service.media.service.StreamProxyService;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时点播接口（docs/03 §4）。
 * 权限矩阵：点播/停止=登录用户（D14 登录用户可停任意会话），会话列表=登录用户
 */
@Tag(name = "实时点播")
@RestController
@RequestMapping("/api/play")
@RequiredArgsConstructor
public class PlayController {

    private final MediaSessionService sessionService;
    private final ChannelDataScopeService dataScopeService;
    private final StreamProxyService streamProxies;

    public record PlayRequest(Long channelDbId, Integer tcpMode, Integer streamProfile) {
    }

    @Operation(summary = "开始点播（同通道同码流复用）")
    @PostMapping
    @PreAuthorize("hasAuthority('device:play')")
    @RateLimit(name = "play", limit = 120, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "play", operation = "开始点播", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Map<String, Object>> play(@RequestBody PlayRequest req, @AuthenticationPrincipal LoginUser user) {
        // 通道级数据权限（docs/09 M2 #5）: 越权通道直接 403
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        MediaSessionService.PlayResult r = sessionService.play(
                req.channelDbId(),
                req.tcpMode() == null ? 1 : req.tcpMode(),
                req.streamProfile() == null ? 1 : req.streamProfile(),
                user == null ? null : user.userId(),
                // M3-B3 阶段2: 下级平台(deviceType=2)通道点播加验 cascade:play（docs/08 §3.2）
                user == null ? null : user.perms());
        return R.ok(Map.of(
                "streamId", r.streamId(),
                "sessionId", r.sessionId(),
                "status", r.status(),
                "urls", r.urls(),
                // H264/H265（B5 多协议）: 前端据此选播放路径/提示硬解能力
                "videoCodec", r.videoCodec() == null ? "" : r.videoCodec()));
    }

    @Operation(summary = "停止点播（viewer-1, 归零关流）")
    @PostMapping("/{streamId}/stop")
    @AuditLog(module = "play", operation = "停止点播", bizType = "channel", bizIdExpr = "#streamId")
    public R<Void> stop(@PathVariable String streamId, @AuthenticationPrincipal LoginUser user) {
        sessionService.stop(streamId, user == null ? null : user.userId());
        return R.ok();
    }

    @Operation(summary = "会话列表（docs/03 §4.3; 受限数据权限用户仅见自己范围内通道的会话）")
    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> sessions(@RequestParam(required = false) String status,
            @AuthenticationPrincipal LoginUser user) {
        var scope = dataScopeService.resolve(user == null ? null : user.userId());
        return R.ok(sessionService.listSessions(status, scope));
    }

    /**
     * 跨节点观看地址（M3-B6 阶段1, docs/03 §4）：按 streamId 取播放地址, 可选 node 指定分发节点
     * （该节点 addStreamProxy 回源后出本节点地址）。不指定 node 返回源节点地址。
     * 观看面权限对齐点播: device:play + 通道数据权限。
     */
    @Operation(summary = "播放地址（可选跨节点分发）")
    @GetMapping("/{streamId}/urls")
    @PreAuthorize("hasAuthority('device:play')")
    public R<Map<String, Object>> urls(@PathVariable String streamId,
            @RequestParam(required = false) String node,
            @AuthenticationPrincipal LoginUser user) {
        // 源会话通道的数据权限（代理流继承源通道可见性）
        Long channelDbId = sessionService.channelDbIdOfStream(streamId);
        dataScopeService.checkAccess(channelDbId, user == null ? null : user.userId());
        return R.ok(streamProxies.ensureProxy(streamId, node));
    }
}
