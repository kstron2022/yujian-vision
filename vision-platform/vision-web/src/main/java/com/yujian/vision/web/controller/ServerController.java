package com.yujian.vision.web.controller;

import com.yujian.vision.common.audit.AuditLog;
import com.yujian.vision.common.api.R;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.service.ServerManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ZLM 节点管理（docs/03 §7, M1-9 A4 契约缺口补齐）。admin 专属。
 */
@Tag(name = "流媒体节点管理")
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('server:list')")
public class ServerController {

    public record ServerRequest(String serverId, String hostIp, String publicIp, Integer httpPort,
            Integer rtpPortStart, Integer rtpPortEnd, String signSecret) {
    }

    private final ServerManageService serverManageService;

    @Operation(summary = "节点列表")
    @GetMapping
    public R<List<GbMediaServerEntity>> list() {
        return R.ok(serverManageService.list());
    }

    @Operation(summary = "新增节点")
    @PostMapping
    @PreAuthorize("hasAuthority('server:add')")
    @AuditLog(module = "sys", operation = "新增节点", bizType = "device", bizIdExpr = "#req.serverId()")
    public R<Void> create(@RequestBody ServerRequest req) {
        GbMediaServerEntity s = new GbMediaServerEntity();
        s.setServerId(req.serverId());
        s.setHostIp(req.hostIp());
        s.setPublicIp(req.publicIp());
        s.setHttpPort(req.httpPort());
        s.setRtpPortStart(req.rtpPortStart());
        s.setRtpPortEnd(req.rtpPortEnd());
        s.setSignSecret(req.signSecret());
        serverManageService.create(s);
        return R.ok();
    }

    @Operation(summary = "修改节点")
    @PutMapping("/{serverId}")
    @PreAuthorize("hasAuthority('server:update')")
    @AuditLog(module = "sys", operation = "修改节点", bizType = "device", bizIdExpr = "#serverId")
    public R<Void> update(@PathVariable String serverId, @RequestBody ServerRequest req) {
        GbMediaServerEntity p = new GbMediaServerEntity();
        p.setHostIp(req.hostIp());
        p.setPublicIp(req.publicIp());
        p.setHttpPort(req.httpPort());
        p.setRtpPortStart(req.rtpPortStart());
        p.setRtpPortEnd(req.rtpPortEnd());
        p.setSignSecret(req.signSecret());
        serverManageService.update(serverId, p);
        return R.ok();
    }

    @Operation(summary = "删除节点")
    @DeleteMapping("/{serverId}")
    @PreAuthorize("hasAuthority('server:delete')")
    @AuditLog(module = "sys", operation = "删除节点", bizType = "device", bizIdExpr = "#serverId")
    public R<Void> delete(@PathVariable String serverId) {
        serverManageService.delete(serverId);
        return R.ok();
    }

    @Operation(summary = "节点流列表（ZLM getMediaList）")
    @GetMapping("/{serverId}/streams")
    public R<Map<String, Object>> streams(@PathVariable String serverId) {
        return R.ok(serverManageService.streams(serverId));
    }
}
