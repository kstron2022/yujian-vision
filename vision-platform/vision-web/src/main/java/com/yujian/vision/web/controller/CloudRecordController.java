package com.yujian.vision.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.service.ChannelDataScopeService;
import com.yujian.vision.service.record.CloudRecordService;
import com.yujian.vision.service.record.RecordPlanService;
import com.yujian.vision.service.record.entity.GbCloudRecordEntity;
import com.yujian.vision.service.record.entity.GbRecordPlanEntity;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.web.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 云端录像 / 录像计划接口（docs/09 M2 #3）。
 * 权限矩阵: 列表/计划查看 = record:query（V9 授权, 复用）;
 * 下载令牌 = record:query + cloud-record:download（V13 5103, B3 P1 收紧）、
 * 录像删除 = cloud-record:delete、计划保存 = record-plan:manage（V13 授权）;
 * 文件下载 = 一次性 token 免登录（token 即凭证 + IP 限流 30 次/分钟, SecurityConfig 精确放行）;
 * 通道敏感读/写均接通道级数据权限（B3 P0-2/P0-3）。
 */
@Tag(name = "云端录像")
@RestController
@RequiredArgsConstructor
public class CloudRecordController {

    private static final DateTimeFormatter SPACE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CloudRecordService cloudRecordService;
    private final RecordPlanService recordPlanService;
    private final ChannelDataScopeService dataScopeService;

    @Operation(summary = "云端录像分页（出参不含服务器文件路径）")
    @GetMapping("/api/cloud-records")
    @PreAuthorize("hasAuthority('record:query')")
    public R<Map<String, Object>> page(@RequestParam(required = false) Long channelDbId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count,
            @AuthenticationPrincipal LoginUser user) {
        Long uid = user == null ? null : user.userId();
        var scope = dataScopeService.resolve(uid);
        if (channelDbId != null) {
            dataScopeService.checkAccess(channelDbId, uid);
        }
        LocalDateTime s = parseTime(start, "start");
        LocalDateTime e = parseTime(end, "end");
        IPage<GbCloudRecordEntity> p = cloudRecordService.page(channelDbId,
                scope.all() ? null : scope.ids(), s, e, page, count);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "签发一次性录像下载令牌（60s）")
    @PostMapping("/api/cloud-records/{id}/download")
    // B3 P1: 下载令牌签发需独立按钮权限（V13 5103, admin+operator）; record:query + 通道级数据权限复验保留
    @PreAuthorize("hasAuthority('record:query') and hasAuthority('cloud-record:download')")
    @AuditLog(module = "record", operation = "云端录像下载令牌签发", bizType = "cloud-record", bizIdExpr = "#id")
    public R<Map<String, Object>> download(@PathVariable Long id,
            @AuthenticationPrincipal LoginUser user) {
        Long uid = user == null ? null : user.userId();
        dataScopeService.checkAccess(cloudRecordService.channelDbIdOf(id), uid);
        String token = cloudRecordService.createDownloadToken(id, uid);
        return R.ok(Map.of(
                "url", "/api/cloud-records/file?token=" + token,
                "expire", CloudRecordService.DL_TOKEN_TTL_SECONDS));
    }

    @Operation(summary = "删除云端录像（连落盘文件, 文件缺失仅删行）")
    @DeleteMapping("/api/cloud-records/{id}")
    @PreAuthorize("hasAuthority('cloud-record:delete')")
    @AuditLog(module = "record", operation = "删除云端录像", bizType = "cloud-record", bizIdExpr = "#id")
    public R<Void> delete(@PathVariable Long id, @AuthenticationPrincipal LoginUser user) {
        Long uid = user == null ? null : user.userId();
        dataScopeService.checkAccess(cloudRecordService.channelDbIdOf(id), uid);
        cloudRecordService.delete(id);
        return R.ok();
    }

    @Operation(summary = "录像文件流式下载（token 即鉴权, 免登录; 一次性消费; IP 限流 30 次/分钟）")
    @GetMapping("/api/cloud-records/file")
    // 免登录端点的暴力枚举防线: token 本体一次性 + 60s TTL, 再加 IP 维度限流（B3 P1）
    @RateLimit(name = "cloud-dl", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.IP)
    public ResponseEntity<Resource> file(@RequestParam String token) {
        CloudRecordService.DownloadPayload payload = cloudRecordService.resolveDownload(token);
        // 数据权限复验: token 只证明"曾有权下载某 id", 不豁免通道级数据权限
        dataScopeService.checkAccess(payload.record().getChannelDbId(), payload.userId());
        String name = StringUtils.hasText(payload.record().getFileName())
                ? payload.record().getFileName()
                : "cloud-record-" + payload.record().getId() + ".mp4";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build());
        long length;
        try {
            length = payload.resource().contentLength();
        } catch (java.io.IOException e) {
            // 文件在删除竞速中被清走: 让容器按分块传输/截断暴露问题, 不再伪造长度
            throw new com.yujian.vision.common.exception.BizException(
                    com.yujian.vision.common.api.ErrorCode.NOT_FOUND, "录像文件读取失败");
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(length)
                .body(payload.resource());
    }

    @Operation(summary = "录像计划列表（全局默认 + 通道行, 通道行带通道名称; 通道行按数据权限过滤）")
    @GetMapping("/api/record-plan")
    @PreAuthorize("hasAuthority('record:query')")
    public R<java.util.List<RecordPlanService.PlanRow>> planList(@AuthenticationPrincipal LoginUser user) {
        var scope = dataScopeService.resolve(user == null ? null : user.userId());
        return R.ok(filterPlanRowsByScope(recordPlanService.list(), scope));
    }

    @Operation(summary = "保存录像计划（planType=1 全局默认 / 2 通道; 通道计划按数据权限校验）")
    @PutMapping("/api/record-plan")
    @PreAuthorize("hasAuthority('record-plan:manage')")
    @AuditLog(module = "record", operation = "保存录像计划", bizType = "record-plan", bizIdExpr = "#req.channelDbId()")
    public R<Map<String, Object>> savePlan(@RequestBody RecordPlanService.PlanUpsert req,
            @AuthenticationPrincipal LoginUser user) {
        if (req != null && req.planType() != null
                && req.planType() == GbRecordPlanEntity.TYPE_CHANNEL) {
            // 通道计划按数据权限校验（全局默认计划维持 record-plan:manage 即可, P0-3）
            dataScopeService.checkAccess(req.channelDbId(), user == null ? null : user.userId());
        }
        return R.ok(Map.of("id", recordPlanService.upsert(req)));
    }

    /**
     * 计划行按数据权限过滤（P0-3）: 全局默认行保留; 通道行 channelDbId 不在许可集时剔除
     * （许可集为空 = 全禁 → 通道行全剔, 仅剩全局行）。
     */
    private static java.util.List<RecordPlanService.PlanRow> filterPlanRowsByScope(
            java.util.List<RecordPlanService.PlanRow> rows, ChannelDataScopeService.ScopeFilter scope) {
        if (scope.all()) {
            return rows;
        }
        java.util.List<RecordPlanService.PlanRow> out = new java.util.ArrayList<>(rows.size());
        for (RecordPlanService.PlanRow r : rows) {
            if (r.channelDbId() == null || scope.ids().contains(r.channelDbId())) {
                out.add(r);
            }
        }
        return out;
    }

    /** 入参时间解析: 支持 yyyy-MM-dd HH:mm:ss 与 yyyy-MM-dd'T'HH:mm:ss 两种（对齐 RecordController） */
    private static LocalDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return LocalDateTime.parse(v, SPACE_FMT);
        } catch (DateTimeParseException ignored) {
            // 落回 'T' 分隔格式
        }
        try {
            return LocalDateTime.parse(v, ISO_FMT);
        } catch (DateTimeParseException e) {
            throw new com.yujian.vision.common.exception.BizException(
                    com.yujian.vision.common.api.ErrorCode.BAD_REQUEST,
                    field + " 时间格式非法，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
