package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.api.R;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.media.config.SnapshotProperties;
import com.yujian.vision.service.media.entity.SnapshotVo;
import com.yujian.vision.service.media.service.SnapshotService;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通道截图接口（M3-B7 阶段2）。
 * 权限矩阵: 手动抓图 = device:play + 通道数据权限 + 用户限流 30/min（docs/10 §1 同点播量级）;
 * 图片读取 = 签名 URL 免登录 + 端点内复验（形制同 /api/cloud-records/file, SecurityConfig 仅放行 GET）;
 * 开关查询 = 登录即可（前端抽屉按 enabled/idleEnabled 决定展示与提示文案）。
 *
 * <p>HA 形态零新增面: /api/channels 前缀已在 HaForwardFilter 的 forwardPrefixes（B6 已有机制）,
 * POST 抓图在备台态自动转发活跃实例执行。</p>
 */
@Tag(name = "通道截图")
@RestController
@RequiredArgsConstructor
public class ChannelSnapshotController {

    private final SnapshotService snapshots;
    private final ChannelDataScopeService dataScopeService;
    private final SnapshotProperties props;

    @Operation(summary = "通道抓图（复用当前流; 空闲通道需开 idle-enabled, 无流且未开则 1014）")
    @PostMapping("/api/channels/{channelDbId}/snapshot")
    @PreAuthorize("hasAuthority('device:play')")
    @RateLimit(name = "snapshot", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "snapshot", operation = "通道抓图", bizType = "channel", bizIdExpr = "#channelDbId")
    public R<SnapshotVo> capture(@PathVariable long channelDbId,
            @AuthenticationPrincipal LoginUser user) {
        // 通道级数据权限（docs/09 M2 #5 同点播口径）: 越权通道直接 403
        dataScopeService.checkAccess(channelDbId, user == null ? null : user.userId());
        return R.ok(snapshots.captureManual(channelDbId));
    }

    @Operation(summary = "截图图片（签名 URL 免登录, 端点内复验; 形制同 /api/cloud-records/file）")
    @GetMapping("/api/channels/{channelDbId}/snapshot")
    // 免登录端点的暴力枚举防线: sign 已绑定 channelDbId+size+expire, 再加 IP 限流兜底
    @RateLimit(name = "snapshot-img", limit = 300, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ResponseEntity<byte[]> image(@PathVariable long channelDbId,
            @RequestParam(defaultValue = "full") String size,
            @RequestParam String sign,
            @RequestParam String expire) {
        if (!snapshots.verifyUrl(channelDbId, size, expire, sign)) {
            // 签名无效/过期/缺参 → 401 包络（全局异常处理器统一出包络, docs/03 §1.1）
            throw new BizException(ErrorCode.UNAUTHORIZED, "截图签名无效或已过期");
        }
        SnapshotService.ServedImage img = snapshots.readForServe(channelDbId, "thumb".equals(size));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(img.body().length)
                // 地址本身带 TTL 签名, 但不代表响应可缓存: 同一 URL 在重新抓图后会返回新画面,
                // 中间缓存/浏览器把旧图当新图回放, 监控场景下就是"看到的是昨天的现场"。
                // no-store 同时挡住 CDN 与磁盘缓存（thumb 是列表缩略图, 本来就不该被共享缓存）。
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(img.body());
    }

    @Operation(summary = "抓图能力开关查询（登录即可, 前端抽屉展示用）")
    @GetMapping("/api/channels/snapshot-config")
    public R<Map<String, Object>> snapshotConfig() {
        return R.ok(Map.of("enabled", props.isEnabled(), "idleEnabled", props.isIdleEnabled()));
    }
}
