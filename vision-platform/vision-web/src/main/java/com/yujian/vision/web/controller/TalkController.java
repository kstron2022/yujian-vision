package com.yujian.vision.web.controller;

import com.yujian.vision.common.api.R;
import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.media.service.TalkSessionService;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * 语音对讲接口（M3-B5, docs/03 §5.x；权限 talk:talk, admin/operator）。
 * start 返回 WebRTC push 地址 → 前端推音频 → begin 拉起 ZLM→设备 RTP 发送 → stop 收尾。
 */
@Tag(name = "语音对讲")
@RestController
@RequestMapping("/api/talk")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('talk:talk')")
public class TalkController {

    private final TalkSessionService svc;
    private final ChannelDataScopeService dataScopeService;

    public record StartRequest(Long channelDbId) {
    }
    public record TalkIdRequest(Long talkId) {
    }

    @Operation(summary = "发起对讲: 建会话+INVITE s=Talk, 返回音频 WS 推流地址")
    @PostMapping("/start")
    @RateLimit(name = "talk", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "talk", operation = "发起对讲", bizType = "channel", bizIdExpr = "#req.channelDbId()")
    public R<Map<String, Object>> start(@RequestBody StartRequest req,
            @AuthenticationPrincipal LoginUser user) {
        dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        TalkSessionService.StartInfo info = svc.start(req.channelDbId(), user == null ? null : user.userId());
        // 相对路径: 前端按当前站点解析 ws(s)://host 并自带 token（同 ws.ts 惯例）
        String ws = "/ws/talk?talkId=" + info.talkId();
        return R.ok(Map.of(
                "talkId", info.talkId(),
                "streamId", info.streamId(),
                "status", info.status(),
                "channelId", info.channelId(),
                "pushWsUrl", ws,
                "reason", info.reason()));
    }

    @Operation(summary = "开始对讲: 等设备应答+音频源就绪后拉起 ZLM→设备音频 RTP 推送")
    @PostMapping("/begin")
    @RateLimit(name = "talk", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    public R<Map<String, Object>> begin(@RequestBody TalkIdRequest req,
            @AuthenticationPrincipal LoginUser user) {
        TalkSessionService.StartInfo info = svc.begin(req.talkId(), user == null ? null : user.userId());
        return R.ok(Map.of(
                "talkId", info.talkId(),
                "status", info.status(),
                "reason", info.reason()));
    }

    @Operation(summary = "结束对讲")
    @PostMapping("/stop")
    @RateLimit(name = "talk", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "talk", operation = "结束对讲", bizType = "talk", bizIdExpr = "#req.talkId()")
    public R<Void> stop(@RequestBody TalkIdRequest req,
            @AuthenticationPrincipal LoginUser user) {
        svc.stop(req.talkId(), "user_stop");
        return R.ok();
    }

    @Operation(summary = "对讲状态查询")
    @GetMapping("/status")
    public R<Map<String, Object>> status(@RequestParam Long talkId,
            @AuthenticationPrincipal LoginUser user) {
        TalkSessionService.StartInfo info = svc.status(talkId);
        return R.ok(Map.of(
                "talkId", info.talkId(),
                "streamId", info.streamId(),
                "status", info.status(),
                "channelId", info.channelId(),
                "reason", info.reason()));
    }
}
