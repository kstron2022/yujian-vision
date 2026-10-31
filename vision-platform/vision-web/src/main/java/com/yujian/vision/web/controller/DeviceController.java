package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.web.ratelimit.RateLimit;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.service.AccessConfigService;
import com.yujian.vision.service.device.service.CatalogSyncService.SyncProgress;
import com.yujian.vision.service.device.onvif.OnvifDeviceManageService;
import com.yujian.vision.service.device.onvif.OnvifDeviceManageService.AddRequest;
import com.yujian.vision.service.device.onvif.OnvifDeviceManageService.TestConnectionRequest;
import com.yujian.vision.service.device.onvif.OnvifDeviceManageService.UpdateRequest;
import com.yujian.vision.service.device.onvif.OnvifDiscoveryService;
import com.yujian.vision.service.device.onvif.OnvifDiscoveryService.DiscoveredView;
import com.yujian.vision.service.device.service.DeviceManageService;
import com.yujian.vision.service.device.service.DeviceCatalogFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备管理接口（docs/03 §3, M1-9 A4）。
 * GET 接口登录即可访问（viewer 预览页需要通道/设备列表）；变更操作按权限点控制。
 */
@Tag(name = "设备管理")
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceCatalogFacade catalogFacade;
    private final DeviceManageService deviceManageService;
    private final AccessConfigService accessConfigService;
    private final OnvifDeviceManageService onvifDeviceManageService;
    private final OnvifDiscoveryService onvifDiscoveryService;
    private final com.yujian.vision.service.device.rtsp.RtspDeviceManageService rtspDeviceManageService;

    public record DeviceUpdateRequest(String name, Integer deviceType, String password,
            Integer heartbeatInterval, Integer heartbeatTimeout, Integer mediaTcpMode,
            String protocolVersion) {
    }

    /** 设备预建档请求（M3-B3 阶段1, docs/08 §3）: 下级平台/设备先配密码再注册 */
    public record DeviceCreateRequest(String deviceId, String name, String password,
            Integer deviceType, String protocolVersion, Integer mediaTcpMode) {
    }

    @Operation(summary = "国标接入配置（接入信息只读 + 自动注册开关 + 默认密码状态; 密码不明文出参）")
    @GetMapping("/access-config")
    @PreAuthorize("hasAuthority('device:update')")
    public R<AccessConfigService.AccessConfigView> getAccessConfig() {
        return R.ok(accessConfigService.get());
    }

    @Operation(summary = "修改接入配置（默认接入密码 ≥8 位 / 自动注册开关; patch 语义）")
    @PutMapping("/access-config")
    @PreAuthorize("hasAuthority('device:update')")
    @AuditLog(module = "device", operation = "接入配置修改", bizType = "device", bizIdExpr = "'access-config'")
    public R<Void> updateAccessConfig(@RequestBody AccessConfigUpdateRequest req) {
        accessConfigService.update(req.defaultPassword(), req.allowAutoRegister());
        return R.ok();
    }

    public record AccessConfigUpdateRequest(String defaultPassword, Integer allowAutoRegister) {
    }

    @Operation(summary = "设备对接信息（服务器信息+设备编码, 供复制发给接入方; 不含接入密码, 由复制方自行补充; 仅 device:update）")
    @GetMapping("/{deviceId}/access-info")
    @PreAuthorize("hasAuthority('device:update')")
    @AuditLog(module = "device", operation = "对接信息查看", bizType = "device", bizIdExpr = "#deviceId")
    public R<Map<String, Object>> deviceAccessInfo(@PathVariable String deviceId) {
        GbDeviceEntity d = deviceManageService.detail(deviceId);
        AccessConfigService.AccessConfigView cfg = accessConfigService.get();
        Map<String, Object> info = new java.util.HashMap<>();
        info.put("serverId", cfg.serverId());
        info.put("domain", cfg.domain());
        info.put("sipIp", cfg.sipIp());
        info.put("sipPort", cfg.sipPort());
        info.put("transports", cfg.transports());
        info.put("deviceId", d.getDeviceId());
        info.put("deviceName", d.getName());
        info.put("deviceType", d.getDeviceType());
        // 接入密码不再下发（2026-09-25 现场要求: 界面/复制不出现明文密码, 由复制方自行补充）
        return R.ok(info);
    }

    @Operation(summary = "设备预建档（下级平台/设备先配接入密码再注册）")
    @PostMapping
    @PreAuthorize("hasAuthority('device:add')")
    @AuditLog(module = "device", operation = "设备新增", bizType = "device", bizIdExpr = "#req.deviceId()")
    public R<GbDeviceEntity> create(@RequestBody DeviceCreateRequest req) {
        return R.ok(deviceManageService.create(req.deviceId(), req.name(), req.password(),
                req.deviceType(), req.protocolVersion(), req.mediaTcpMode()));
    }

    @Operation(summary = "设备分页列表（online/keyword/accessProtocol 过滤）")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Integer online,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String accessProtocol,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int count) {
        var p = deviceManageService.page(online, keyword, accessProtocol, page, count);
        return R.ok(Map.of("total", p.getTotal(), "list", p.getRecords()));
    }

    @Operation(summary = "设备详情")
    @GetMapping("/{deviceId}")
    public R<GbDeviceEntity> detail(@PathVariable String deviceId) {
        return R.ok(deviceManageService.detail(deviceId));
    }

    @Operation(summary = "触发目录同步（进行中返回 2001）")
    @PostMapping("/{deviceId}/catalog/refresh")
    @PreAuthorize("hasAuthority('device:refresh')")
    @AuditLog(module = "device", operation = "目录刷新", bizType = "device", bizIdExpr = "#deviceId")
    public R<Void> refreshCatalog(@PathVariable String deviceId) {
        catalogFacade.refresh(deviceManageService.detail(deviceId));
        return R.ok();
    }

    @Operation(summary = "目录同步进度（running=false 表示当前无同步会话）")
    @GetMapping("/{deviceId}/catalog/progress")
    public R<SyncProgress> progress(@PathVariable String deviceId) {
        return R.ok(catalogFacade.progress(deviceManageService.detail(deviceId)));
    }

    @Operation(summary = "修改设备参数（名称/类型/密码/心跳/收流方式/协议版本）")
    @PutMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('device:update')")
    @AuditLog(module = "device", operation = "设备编辑", bizType = "device", bizIdExpr = "#deviceId")
    public R<Void> update(@PathVariable String deviceId, @RequestBody DeviceUpdateRequest req) {
        deviceManageService.update(deviceId, req.name(), req.deviceType(), req.password(),
                req.heartbeatInterval(), req.heartbeatTimeout(), req.mediaTcpMode(),
                req.protocolVersion());
        return R.ok();
    }

    @Operation(summary = "删除设备（软删设备与通道）")
    @DeleteMapping("/{deviceId}")
    @PreAuthorize("hasAuthority('device:delete')")
    @AuditLog(module = "device", operation = "设备删除", bizType = "device", bizIdExpr = "#deviceId")
    public R<Void> delete(@PathVariable String deviceId) {
        deviceManageService.delete(deviceId);
        return R.ok();
    }

    // ==================== M4-B1 ONVIF（docs/13 §6.1） ====================

    @Operation(summary = "ONVIF 测试连接（设备信息+通道预览, 添加抽屉一次拿全）")
    @PostMapping("/onvif/test-connection")
    @PreAuthorize("hasAuthority('device:add')")
    @RateLimit(name = "onvif-test", limit = 30, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "device", operation = "ONVIF 测试连接", bizType = "device", bizIdExpr = "#req.ip()")
    public R<OnvifDeviceManageService.TestConnectionView> onvifTestConnection(
            @RequestBody TestConnectionRequest req) {
        return R.ok(onvifDeviceManageService.testConnection(req));
    }

    @Operation(summary = "ONVIF 设备手填添加（IP+端口+账密, 建档即目录同步; 失败回滚不留半成品）")
    @PostMapping("/onvif")
    @PreAuthorize("hasAuthority('device:add')")
    @AuditLog(module = "device", operation = "ONVIF 设备新增", bizType = "device",
            bizIdExpr = "#req.ip()")
    public R<GbDeviceEntity> onvifAdd(@RequestBody AddRequest req) {
        return R.ok(onvifDeviceManageService.add(req));
    }

    @Operation(summary = "ONVIF 同网段探测（WS-Discovery, 仅主实例; 发现≠接入, 建档仍走手填+账密）")
    @PostMapping("/onvif/discovery/probe")
    @PreAuthorize("hasAuthority('device:add')")
    @RateLimit(name = "onvif-discovery", limit = 6, windowSeconds = 60, dimension = RateLimit.Dimension.USER)
    @AuditLog(module = "device", operation = "ONVIF 同网段探测", bizType = "device")
    public R<java.util.List<DiscoveredView>> onvifDiscoveryProbe() {
        return R.ok(onvifDiscoveryService.probe());
    }

    @Operation(summary = "ONVIF 设备参数修改（名称/地址/账密/探活周期; 密码留空不修改）")
    @PutMapping("/onvif/{deviceId}")
    @PreAuthorize("hasAuthority('device:update')")
    @AuditLog(module = "device", operation = "ONVIF 设备编辑", bizType = "device",
            bizIdExpr = "#deviceId")
    public R<Void> onvifUpdate(@PathVariable String deviceId, @RequestBody UpdateRequest req) {
        onvifDeviceManageService.update(deviceId, req);
        return R.ok();
    }

    // ==================== M5-B1 拉流代理（docs/14 §5） ====================

    @Operation(summary = "拉流代理新增（RTSP 流地址接入为设备, 建档即自动建单通道）")
    @PostMapping("/rtsp")
    @PreAuthorize("hasAuthority('device:add')")
    @AuditLog(module = "device", operation = "拉流代理新增", bizType = "device")
    public R<GbDeviceEntity> rtspAdd(@RequestBody com.yujian.vision.service.device.rtsp.RtspDeviceManageService.CreateRequest req) {
        return R.ok(rtspDeviceManageService.create(req));
    }

    @Operation(summary = "拉流代理批量导入（每行 URL 一台设备, 支持名称,URL 两格式; 结果汇总）")
    @PostMapping("/rtsp/batch-import")
    @PreAuthorize("hasAuthority('device:add')")
    @AuditLog(module = "device", operation = "拉流代理批量导入", bizType = "device")
    public R<com.yujian.vision.service.device.rtsp.RtspDeviceManageService.BatchImportResult> rtspBatchImport(
            @RequestBody com.yujian.vision.service.device.rtsp.RtspDeviceManageService.BatchImportRequest req) {
        return R.ok(rtspDeviceManageService.batchImport(req));
    }

    @Operation(summary = "拉流代理编辑（名称/源地址盲显留空不改/传输/常驻开关/探活周期）")
    @PutMapping("/rtsp/{deviceId}")
    @PreAuthorize("hasAuthority('device:update')")
    @AuditLog(module = "device", operation = "拉流代理编辑", bizType = "device",
            bizIdExpr = "#deviceId")
    public R<Void> rtspUpdate(@PathVariable String deviceId,
            @RequestBody com.yujian.vision.service.device.rtsp.RtspDeviceManageService.UpdateRequest req) {
        rtspDeviceManageService.update(deviceId, req);
        return R.ok();
    }
}
