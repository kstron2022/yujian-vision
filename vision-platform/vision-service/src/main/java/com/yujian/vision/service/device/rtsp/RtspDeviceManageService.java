package com.yujian.vision.service.device.rtsp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.onvif.DeviceProtocolConstants;
import com.yujian.vision.service.device.onvif.OnvifCodeAllocator;
import com.yujian.vision.service.media.service.StreamProxyService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 拉流代理（RTSP 流地址接入）设备管理（M5-B1, docs/14 §5）: 单个新增/批量导入/编辑/删除联动。
 * 档案形态对齐 ONVIF add 模式: 编码合成（复用 OnvifCodeAllocator——类在 onvif 包但逻辑通用:
 * 平台域前缀+132 类型段+Redis 序列+设备/通道全局查重）、失败硬删回滚不留半成品、
 * update patch 语义+协议守卫。source_url 整串 AES 加密（口径同 password 列）, 编辑盲显留空=不修改。
 * 删除联动: 拆常驻代理+清常驻索引（按需代理由会话生命周期自然清理）,
 * 由 DeviceManageService.delete 对 RTSP 设备回调 deleteProxyOnRemove。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtspDeviceManageService {

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final AesCipher aes;
    private final OnvifCodeAllocator codeAllocator;
    private final StreamProxyService streamProxies;

    // ---- 请求 DTO（sourceUrl 只进不出, 编辑盲显留空=不修改） ----

    public record CreateRequest(String name, String sourceUrl, String rtspTransport,
            Integer alwaysOn, Integer heartbeatInterval) {
    }

    public record BatchImportRequest(String text, String rtspTransport, Integer alwaysOn) {
    }

    /** 批量导入结果汇总（成功/重复/失败计数 + 失败明细行号与原因） */
    public record BatchImportResult(int total, int success, int duplicated, List<FailedLine> failed) {
    }

    public record FailedLine(int lineNo, String line, String reason) {
    }

    public record UpdateRequest(String name, String sourceUrl, String rtspTransport,
            Integer alwaysOn, Integer heartbeatInterval) {
    }

    /** RTSP 实况 streamId 前缀与 MediaSessionService.LIVE_STREAM_PREFIX 同值（避免 service 间循环依赖） */
    static final String LIVE_STREAM_PREFIX = "gb_play_";

    // ---- 新增（单个） ----

    public GbDeviceEntity create(CreateRequest req) {
        String transport = RtspSourceSupport.validateTransport(req.rtspTransport());
        String url = RtspSourceSupport.validateSourceUrl(req.sourceUrl());
        boolean alwaysOn = normalizeAlwaysOn(req.alwaysOn()) == 1;
        return doCreate(StringUtils.hasText(req.name()) ? req.name().trim() : null,
                url, transport, alwaysOn, intervalOf(req.heartbeatInterval()));
    }

    /** 自动命名: 拉流代理-{存量RTSP设备数+1}（名字非唯一约束, 并发重名无碍） */
    private String autoName() {
        Long cnt = deviceMapper.selectCount(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.RTSP)
                .isNull(GbDeviceEntity::getDeletedTime));
        return "拉流代理-" + (cnt + 1);
    }

    private static int intervalOf(Integer interval) {
        if (interval == null) {
            return 60;
        }
        if (interval < 10 || interval > 3600) {
            throw new BizException(ErrorCode.BAD_REQUEST, "探活周期须为 10~3600 秒: " + interval);
        }
        return interval;
    }

    /** URL host 提取（校验已过, 解析失败按无 host 兜底） */
    static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 建档+自动建单通道, 失败硬删回滚（同 ONVIF rollback 先例: 刚插入、无外部引用） */
    private GbDeviceEntity doCreate(String name, String url, String transport, boolean alwaysOn,
            int heartbeatInterval) {
        GbDeviceEntity device = new GbDeviceEntity();
        device.setDeviceId(codeAllocator.nextDeviceCode());
        device.setName(name != null ? name : autoName());
        device.setDeviceType(1);
        device.setAccessProtocol(DeviceProtocolConstants.RTSP);
        device.setSourceUrl(aes.encrypt(url));
        device.setRtspTransport(transport);
        device.setAlwaysOn(alwaysOn ? 1 : 0);
        device.setHeartbeatInterval(heartbeatInterval);
        device.setHeartbeatTimeout(3);
        device.setOnline(0);
        device.setRegisterTime(LocalDateTime.now());
        try {
            deviceMapper.insert(device);
            GbDeviceChannelEntity ch = new GbDeviceChannelEntity();
            ch.setDeviceDbId(device.getId());
            ch.setChannelId(codeAllocator.nextChannelCode());
            ch.setName(device.getName());
            ch.setCustomName(device.getName());
            ch.setManufacturer("RTSP");
            ch.setIp(hostOf(url));
            ch.setPtzType(0);
            ch.setStatus(0);
            ch.setCatalogTime(LocalDateTime.now());
            channelMapper.insert(ch);
        } catch (RuntimeException e) {
            rollback(device.getId());
            throw e;
        }
        log.info("拉流代理设备已添加: name={} code={} transport={} alwaysOn={}",
                device.getName(), device.getDeviceId(), transport, alwaysOn ? 1 : 0);
        return device;
    }

    /** 添加失败回滚: 硬删新档与新通道（同 OnvifDeviceManageService.rollback 模式） */
    private void rollback(Long deviceDbId) {
        try {
            deviceMapper.deleteById(deviceDbId);
            channelMapper.delete(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .eq(GbDeviceChannelEntity::getDeviceDbId, deviceDbId));
        } catch (Exception e) {
            log.error("拉流代理添加失败回滚异常(残留新档可手动删除): deviceDbId={}", deviceDbId, e);
        }
    }

    // ---- 批量导入（每行 URL 一台设备, 模型 A） ----

    public BatchImportResult batchImport(BatchImportRequest req) {
        String transport = RtspSourceSupport.validateTransport(req.rtspTransport());
        boolean alwaysOn = normalizeAlwaysOn(req.alwaysOn()) == 1;
        List<RtspSourceSupport.ParsedLine> lines = RtspSourceSupport.parseLines(req.text());
        // 查重基线: 存量 RTSP 设备明文 URL（解密比对, 设备量级小可接受） + 本批次内已出现的 URL
        Set<String> existingUrls = loadExistingUrls();
        Set<String> batchUrls = new HashSet<>();
        int success = 0;
        int duplicated = 0;
        List<FailedLine> failed = new ArrayList<>();
        for (RtspSourceSupport.ParsedLine line : lines) {
            String url;
            String name;
            try {
                url = RtspSourceSupport.validateSourceUrl(line.url());
            } catch (Exception e) {
                failed.add(new FailedLine(line.lineNo(), line.url(), e.getMessage()));
                continue;
            }
            if (existingUrls.contains(url) || batchUrls.contains(url)) {
                duplicated++;
                continue;
            }
            name = StringUtils.hasText(line.name()) ? line.name() : autoName();
            try {
                doCreate(name, url, transport, alwaysOn, 60);
                batchUrls.add(url);
                success++;
            } catch (Exception e) {
                failed.add(new FailedLine(line.lineNo(), line.url(), e.getMessage()));
            }
        }
        return new BatchImportResult(lines.size(), success, duplicated, failed);
    }

    /** 存量 RTSP 设备明文 URL 集合（批量导入查重用; 解密失败的行跳过不计重复） */
    private Set<String> loadExistingUrls() {
        List<GbDeviceEntity> rows = deviceMapper.selectList(
                new LambdaQueryWrapper<GbDeviceEntity>()
                        .eq(GbDeviceEntity::getAccessProtocol, DeviceProtocolConstants.RTSP)
                        .isNull(GbDeviceEntity::getDeletedTime));
        Set<String> urls = new HashSet<>();
        for (GbDeviceEntity d : rows) {
            if (d.getSourceUrl() == null || d.getSourceUrl().isBlank()) {
                continue;
            }
            try {
                urls.add(aes.decrypt(d.getSourceUrl()));
            } catch (Exception e) {
                log.warn("存量拉流地址解密失败(跳过查重): device={} err={}", d.getDeviceId(), e.getMessage());
            }
        }
        return urls;
    }

    // ---- 编辑（patch 语义+协议守卫; 常驻开关/传输/地址变更对常驻代理即时生效） ----

    public void update(String deviceId, UpdateRequest req) {
        GbDeviceEntity device = selectRtsp(deviceId);
        if (device == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在或非拉流代理设备: " + deviceId);
        }
        boolean proxyRebuildNeeded = false;
        if (StringUtils.hasText(req.name())) {
            if (req.name().trim().length() > 128) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设备名称过长（≤128）");
            }
            device.setName(req.name().trim());
        }
        if (StringUtils.hasText(req.sourceUrl())) {
            String url = RtspSourceSupport.validateSourceUrl(req.sourceUrl());
            String stored = device.getSourceUrl() == null || device.getSourceUrl().isBlank()
                    ? "" : aes.decrypt(device.getSourceUrl());
            if (!url.equals(stored)) {
                device.setSourceUrl(aes.encrypt(url));
                proxyRebuildNeeded = true;
            }
        }
        if (StringUtils.hasText(req.rtspTransport()) || req.alwaysOn() != null) {
            String newTransport = StringUtils.hasText(req.rtspTransport())
                    ? RtspSourceSupport.validateTransport(req.rtspTransport())
                    : device.getRtspTransport();
            int newAlwaysOn = req.alwaysOn() != null ? normalizeAlwaysOn(req.alwaysOn()) : alwaysOnOf(device);
            if (!newTransport.equals(device.getRtspTransport())
                    || newAlwaysOn != alwaysOnOf(device)) {
                device.setRtspTransport(newTransport);
                device.setAlwaysOn(newAlwaysOn);
                proxyRebuildNeeded = true;
            }
        }
        if (req.heartbeatInterval() != null) {
            device.setHeartbeatInterval(intervalOf(req.heartbeatInterval()));
        }
        deviceMapper.updateById(device);
        if (proxyRebuildNeeded) {
            handleProxyOnChange(device.getId());
        }
        log.info("拉流代理设备已更新: device={} urlChanged/transportChanged/alwaysOnChanged={}",
                deviceId, proxyRebuildNeeded);
    }

    /** 协议守卫查询: 仅 access_protocol=RTSP 的活跃行 */
    private GbDeviceEntity selectRtsp(String deviceId) {
        GbDeviceEntity d = deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
        if (d == null || !DeviceProtocolConstants.RTSP.equals(d.getAccessProtocol())) {
            return null;
        }
        return d;
    }

    /** alwaysOn 取值域守卫（评审 P2）: 非 0/1 落库会让查询 eq(1) 与 ==1 判定口径静默漂移 */
    private static int normalizeAlwaysOn(Integer alwaysOn) {
        if (alwaysOn == null) {
            return 0;
        }
        if (alwaysOn != 0 && alwaysOn != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "常驻拉流开关仅支持 0/1: " + alwaysOn);
        }
        return alwaysOn;
    }
    private static int alwaysOnOf(GbDeviceEntity device) {
        return device.getAlwaysOn() != null && device.getAlwaysOn() == 1 ? 1 : 0;
    }

    /**
     * 建流要素变更处置: 拆常驻代理+清常驻索引, 收敛循环下轮按新地址/传输/开关重拉;
     * 按需模式无代理常驻（活跃会话走到自然结束）。
     */
    private void handleProxyOnChange(Long deviceDbId) {
        GbDeviceChannelEntity ch = channelMapper.selectOne(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, deviceDbId)
                        .isNull(GbDeviceChannelEntity::getDeletedTime)
                        .last("LIMIT 1"));
        if (ch == null) {
            return;
        }
        String streamId = LIVE_STREAM_PREFIX + ch.getChannelId();
        for (Map.Entry<String, String> e : streamProxies.alwaysOnEntries(streamId).entrySet()) {
            try {
                streamProxies.closeStreamsOn(e.getKey(), streamId);
            } catch (Exception ex) {
                log.warn("常驻代理拆除失败(收敛循环兜底重建): stream={} node={}", streamId, e.getKey(), ex);
            }
        }
        streamProxies.clearAlwaysOn(streamId);
        log.info("建流要素变更, 常驻代理已拆除待收敛重建: device={} stream={}", deviceDbId, streamId);
    }

    /**
     * 删除设备联动（DeviceManageService.delete 对 RTSP 设备回调, 软删前执行）:
     * 拆常驻代理+清常驻索引。按需源代理随活跃会话清理链路自然拆除。
     */
    public void deleteProxyOnRemove(String deviceId) {
        GbDeviceEntity d = selectRtsp(deviceId);
        if (d == null) {
            return;
        }
        GbDeviceChannelEntity ch = channelMapper.selectOne(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, d.getId())
                        .isNull(GbDeviceChannelEntity::getDeletedTime)
                        .last("LIMIT 1"));
        if (ch != null) {
            String streamId = LIVE_STREAM_PREFIX + ch.getChannelId();
            for (Map.Entry<String, String> e : streamProxies.alwaysOnEntries(streamId).entrySet()) {
                streamProxies.closeStreamsOn(e.getKey(), streamId);
            }
            streamProxies.clearAlwaysOn(streamId);
        }
        log.info("拉流代理设备删除联动完成: device={}", deviceId);
    }
}
