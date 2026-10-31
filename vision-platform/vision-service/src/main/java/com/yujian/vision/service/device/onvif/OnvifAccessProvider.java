package com.yujian.vision.service.device.onvif;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.onvif.OnvifClient;
import com.yujian.vision.onvif.OnvifUris;
import com.yujian.vision.onvif.model.OnvifCallException;
import com.yujian.vision.onvif.model.OnvifDeviceInfo;
import com.yujian.vision.onvif.model.OnvifProfile;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.service.PresetQueryService;
import com.yujian.vision.service.device.spi.DeviceAccessProvider;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ONVIF 接入实现（docs/13 §3.2/§4）：探活=GetDeviceInformation 轮询, 目录按 SourceToken 归组收敛。
 * 通道身份键=onvif_source_token；channel_id 存合成 20 位编码（级联/分组/目录树统一吃这列）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnvifAccessProvider implements DeviceAccessProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final AesCipher aes;
    private final OnvifCodeAllocator codeAllocator;
    private final StreamEventPublisher eventPublisher;

    private final OnvifClient client = new OnvifClient();

    @Override
    public DeviceProtocol protocol() {
        return DeviceProtocol.ONVIF;
    }

    /** 探活成功 → 刷 keepalive_time; 离线→上线发事件; 厂商信息空缺补全（不覆盖已有值） */
    @Override
    public void probe(GbDeviceEntity device) {
        try {
            OnvifDeviceInfo info = client.deviceInfo(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device));
            LocalDateTime now = LocalDateTime.now();
            GbDeviceEntity up = new GbDeviceEntity();
            up.setId(device.getId());
            up.setKeepaliveTime(now);
            boolean wentOnline = device.getOnline() == null || device.getOnline() != 1;
            if (wentOnline) {
                up.setOnline(1);
                if (device.getRegisterTime() == null) {
                    up.setRegisterTime(now);
                }
            }
            if (!StringUtils.hasText(device.getManufacturer()) && StringUtils.hasText(info.manufacturer())) {
                up.setManufacturer(info.manufacturer());
            }
            if (!StringUtils.hasText(device.getModel()) && StringUtils.hasText(info.model())) {
                up.setModel(info.model());
            }
            if (!StringUtils.hasText(device.getFirmware()) && StringUtils.hasText(info.firmware())) {
                up.setFirmware(info.firmware());
            }
            deviceMapper.updateById(up);
            if (wentOnline) {
                log.info("ONVIF 设备探活上线: {}", device.getDeviceId());
                eventPublisher.deviceOnline(device.getDeviceId(), device.getName());
            }
        } catch (OnvifCallException e) {
            // 探活失败不等于立即离线: 交由 DeviceOfflineTask 按 interval*timeout 统一判定（协议无关）
            log.warn("ONVIF 探活失败(交由离线判定): {} err={}", device.getDeviceId(), OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /** 目录同步：全量比对收敛（新增/更新/软删缺失通道），成功同时刷新探活状态 */
    @Override
    public void syncCatalog(GbDeviceEntity device) {
        Map<String, List<OnvifProfile>> bySource = fetchCatalog(device);
        // 占位组 "": SourceToken 无匹配 VideoSource 的 Profile（个别厂商不回 token）, 不导入只告警
        List<OnvifProfile> orphan = bySource.remove("");
        if (orphan != null && !orphan.isEmpty()) {
            log.warn("ONVIF 目录存在未归属 VideoSource 的 Profile, 已跳过: device={} count={}",
                    device.getDeviceId(), orphan.size());
        }
        refreshProbeState(device);
        converge(device, bySource);
    }

    private Map<String, List<OnvifProfile>> fetchCatalog(GbDeviceEntity device) {
        try {
            return client.catalog(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device));
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "ONVIF 调用失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /** 探活状态刷新（目录同步成功即视为探活成功, 在线翻转口径同 probe） */
    private void refreshProbeState(GbDeviceEntity device) {
        LocalDateTime now = LocalDateTime.now();
        GbDeviceEntity up = new GbDeviceEntity();
        up.setId(device.getId());
        up.setKeepaliveTime(now);
        boolean wentOnline = device.getOnline() == null || device.getOnline() != 1;
        if (wentOnline) {
            up.setOnline(1);
            if (device.getRegisterTime() == null) {
                up.setRegisterTime(now);
            }
        }
        deviceMapper.updateById(up);
        device.setOnline(1);
        if (wentOnline) {
            eventPublisher.deviceOnline(device.getDeviceId(), device.getName());
        }
    }

    /** 目录收敛（与 GB CatalogSyncService.converge 同语义, 身份键=onvif_source_token） */
    private void converge(GbDeviceEntity device, Map<String, List<OnvifProfile>> bySource) {
        List<GbDeviceChannelEntity> existing = channelMapper.selectList(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getDeviceDbId, device.getId())
                        .isNull(GbDeviceChannelEntity::getDeletedTime));
        Map<String, GbDeviceChannelEntity> bySourceToken = new HashMap<>();
        for (GbDeviceChannelEntity ch : existing) {
            if (StringUtils.hasText(ch.getOnvifSourceToken())) {
                bySourceToken.put(ch.getOnvifSourceToken(), ch);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        int updated = 0;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<OnvifProfile>> e : bySource.entrySet()) {
            String sourceToken = e.getKey();
            if (!seen.add(sourceToken)) {
                continue;
            }
            List<OnvifProfile> profiles = sortProfiles(e.getValue());
            GbDeviceChannelEntity ch = bySourceToken.get(sourceToken);
            boolean isNew = ch == null;
            if (isNew) {
                ch = new GbDeviceChannelEntity();
                ch.setDeviceDbId(device.getId());
                ch.setChannelId(codeAllocator.nextChannelCode());
                ch.setCreateTime(now);
            }
            applyProfiles(ch, device, sourceToken, profiles);
            if (isNew) {
                channelMapper.insert(ch);
                inserted++;
            } else {
                channelMapper.updateById(ch);
                updated++;
            }
        }
        int removed = 0;
        for (GbDeviceChannelEntity ch : existing) {
            if (StringUtils.hasText(ch.getOnvifSourceToken()) && !seen.contains(ch.getOnvifSourceToken())) {
                GbDeviceChannelEntity del = new GbDeviceChannelEntity();
                del.setId(ch.getId());
                del.setDeletedTime(now);
                channelMapper.updateById(del);
                removed++;
            }
        }
        log.info("ONVIF 目录收敛完成: device={} 源数{} 入库{} 更新{} 下线{}",
                device.getDeviceId(), bySource.size(), inserted, updated, removed);
        eventPublisher.channelChange(device.getDeviceId());
    }

    /** VideoSource → 通道实体: 名称取主码流 Profile, ptzType 由 PTZConfiguration 推导(4=遥控), 档位落 JSON */
    private void applyProfiles(GbDeviceChannelEntity ch, GbDeviceEntity device,
            String sourceToken, List<OnvifProfile> profiles) {
        OnvifProfile main = profiles.isEmpty() ? null : profiles.get(0);
        // name 语义对齐 GB applyItemFields(设备上报覆盖): 设备侧改名后平台跟随; customName 用户自定义保留
        if (main != null && StringUtils.hasText(main.name())) {
            ch.setName(main.name());
        } else if (ch.getName() == null || ch.getName().isBlank()) {
            ch.setName(sourceToken);
        }
        ch.setCustomName(ch.getCustomName() == null ? "" : ch.getCustomName());
        ch.setManufacturer(ch.getManufacturer() == null || ch.getManufacturer().isBlank()
                ? device.getManufacturer() : ch.getManufacturer());
        ch.setModel(ch.getModel() == null || ch.getModel().isBlank() ? device.getModel() : ch.getModel());
        ch.setAddress(device.getIp() == null ? "" : device.getIp());
        ch.setParental(0);
        ch.setParentId("");
        ch.setStatus(1);
        ch.setIp(device.getIp());
        ch.setPort(device.getPort());
        // PTZConfiguration 存在 → 推导 1(球机): 上级平台(LiveGBS 等)按 PTZType 裁剪云台 UI,
        // 球机获得完整控制面(方向/变倍/预置位); 曾推导 4(遥控枪机)被部分上级弱化控制入口
        ch.setPtzType(profiles.stream().anyMatch(OnvifProfile::ptz) ? 1 : 0);
        ch.setMobile(0);
        ch.setSubCount(0);
        ch.setOnvifSourceToken(sourceToken);
        ch.setOnvifProfileToken(main == null ? "" : main.token());
        ch.setOnvifProfiles(toJson(profiles));
        ch.setCatalogTime(LocalDateTime.now());
    }

    /**
     * 媒体面分流点（M4-B2, docs/13 §5.1）：按码流档位选 profile → GetStreamUri →
     * 归一为平台可拉流的 RTSP 地址（嵌 ONVIF 账密; 主机与手填 IP 不一致时按手填 IP 重组）。
     * 档位: 1=主码流(首档) / 2=子码流(次档, 单档设备回主码流), 档位序 = 目录同步时按像素面积降序。
     */
    @Override
    public String resolveStreamUrl(GbDeviceEntity device, GbDeviceChannelEntity channel, int streamProfile) {
        List<OnvifProfile> profiles = parseProfiles(channel.getOnvifProfiles());
        String mainToken = channel.getOnvifProfileToken();
        if (profiles.isEmpty() || mainToken == null || mainToken.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道无可用码流档位, 请先重新同步目录");
        }
        String token = pickToken(profiles, mainToken, streamProfile);
        String uri;
        try {
            uri = client.streamUri(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device), token);
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 取流地址获取失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
        if (uri == null || uri.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备未返回流地址(该固件可能不支持 GetStreamUri)");
        }
        String pullUrl = OnvifUris.normalizePullUrl(uri, device.getOnvifUsername(),
                plainPassword(device), device.getIp());
        log.info("ONVIF 取流地址已归一: device={} profile={} 主机不一致已重组={}",
                device.getDeviceId(), token, !pullUrl.contains("@" + device.getIp()));
        return pullUrl;
    }

    /**
     * 云台方向/变倍/停止/聚焦（M4-B2 + 2026-09-25 现场补齐, docs/13 §5）：GB 指令语义 → ONVIF。
     * 方向/变倍 = ContinuousMove/Stop（PTZ 服务）; 聚焦 = Imaging 服务 Move/Stop（timg, 目标是
     * VideoSourceToken）; 光圈无 ONVIF 标准对映（IP 相机自动光圈）给明确业务错误。
     * PTZ/Imaging 服务地址每次经 GetCapabilities 解析（低频操作, 不缓存）。
     */
    @Override
    public void controlPtz(GbDeviceEntity device, GbDeviceChannelEntity channel, String command, int speed) {
        String profile = requireProfile(channel);
        double v = Math.max(0.0, Math.min(1.0, speed / 100.0));
        double x = 0;
        double y = 0;
        double z = 0;
        switch (command) {
            case "UP" -> y = v;
            case "DOWN" -> y = -v;
            case "LEFT" -> x = -v;
            case "RIGHT" -> x = v;
            case "ZOOM_IN" -> z = v;
            case "ZOOM_OUT" -> z = -v;
            case "FOCUS_NEAR" -> {
                imagingFocus(device, channel, v);
                return;
            }
            case "FOCUS_FAR" -> {
                imagingFocus(device, channel, -v);
                return;
            }
            case "IRIS_OPEN", "IRIS_CLOSE" -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "ONVIF 协议不支持光圈控制（IP 相机为自动光圈, 如需手动光圈请走设备 Web 界面）");
            case "STOP" -> {
                call(device, () -> client.ptzStop(ptzUrl(device), device.getOnvifUsername(),
                        plainPassword(device), profile));
                // PTZ Stop 不停聚焦（Imaging 服务各自惯性运动）——松开聚焦键前端也发 STOP, 双停兜底
                call(device, () -> client.imagingStop(imagingUrl(device), device.getOnvifUsername(),
                        plainPassword(device), sourceToken(channel)));
                return;
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "ONVIF 通道不支持云台指令: " + command);
        }
        double fx = x;
        double fy = y;
        double fz = z;
        call(device, () -> client.ptzMove(ptzUrl(device), device.getOnvifUsername(),
                plainPassword(device), profile, fx, fy, fz));
    }

    /** 预置位（M4-B2）：SET/GOTO/DELETE → SetPreset/GotoPreset/RemovePreset, PresetToken=GB 预置位号 */
    @Override
    public void controlPreset(GbDeviceEntity device, GbDeviceChannelEntity channel, String action, int presetNum) {
        String profile = requireProfile(channel);
        String operation = switch (action) {
            case "SET" -> "SetPreset";
            case "GOTO" -> "GotoPreset";
            case "DELETE" -> "RemovePreset";
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "未知预置位动作: " + action);
        };
        call(device, () -> client.ptzPreset(ptzUrl(device), device.getOnvifUsername(),
                plainPassword(device), profile, operation, String.valueOf(presetNum)));
    }

    /**
     * 预置位检索（2026-09-25 现场补齐）: GetPresets → PresetItem(token, name)。
     * 注意: token 为设备侧字符串（海康通常为数字串, 与 GB 预置位号兼容）——非数字 token 的
     * 设备 GOTO 按编号下发可能不命中, 属已知边界（控制面仍按 GB 语义传预置位号）。
     */
    @Override
    public java.util.List<PresetQueryService.PresetItem> queryPresets(
            GbDeviceEntity device, GbDeviceChannelEntity channel) {
        String profile = requireProfile(channel);
        try {
            java.util.List<com.yujian.vision.onvif.model.OnvifPreset> presets =
                    client.ptzPresets(ptzUrl(device), device.getOnvifUsername(),
                            plainPassword(device), profile);
            java.util.List<PresetQueryService.PresetItem> out = new java.util.ArrayList<>(presets.size());
            for (com.yujian.vision.onvif.model.OnvifPreset p : presets) {
                out.add(new PresetQueryService.PresetItem(p.token(), p.name()));
            }
            log.info("ONVIF 预置位检索完成: channel={} count={}", channel.getChannelId(), out.size());
            return out;
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 预置位检索失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /** URI query 里 starttime/endtime 设置/替换为请求窗口（mtime 紧凑格式, OnvifTime.toMtime; 包可见便于单测） */
    static String withWindow(String uri, LocalDateTime start, LocalDateTime end) {
        String u = setQueryParam(uri, "starttime", com.yujian.vision.onvif.OnvifTime.toMtime(start));
        u = setQueryParam(u, "endtime", com.yujian.vision.onvif.OnvifTime.toMtime(end));
        return u;
    }

    /** 单个 query 参数设置/替换（评审 B3-P2/P3: 无 query 的裸 URI 用 "?" 首参, 不产出 "?&";
     *  只替换首个重复参数; (?i) 兼容 StartTime= 形态固件防双参数） */
    private static String setQueryParam(String uri, String key, String value) {
        String kv = key + "=" + value;
        if (uri.matches(".*[?&](?i)" + key + "=[^&]*.*")) {
            return uri.replaceFirst("([?&])(?i)" + key + "=[^&]*", "$1" + kv);
        }
        return uri + (uri.contains("?") ? "&" : "?") + kv;
    }

    /**
     * 海康 tracks URI 推导: 实况 /Streaming/Channels/{n} → 回放 /Streaming/tracks/{n}?starttime=&endtime=。
     * 非 Channels 形态（ONVIF 标准路径等）回 null, 调用方给明确业务错误。
     */
    static String deriveTracksUrl(String liveUri, LocalDateTime start, LocalDateTime end) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/Streaming/Channels/(\\d+)").matcher(liveUri);
        if (!m.find()) {
            return null;
        }
        String trackId = m.group(1);
        String query = "starttime=" + com.yujian.vision.onvif.OnvifTime.toMtime(start)
                + "&endtime=" + com.yujian.vision.onvif.OnvifTime.toMtime(end);
        String base = liveUri.contains("?") ? liveUri.substring(0, liveUri.indexOf('?')) : liveUri;
        return base.replace("/Streaming/Channels/" + trackId, "/Streaming/tracks/" + trackId)
                + "?" + query;
    }

    private static String requireProfile(GbDeviceChannelEntity channel) {
        String profile = channel.getOnvifProfileToken();
        if (profile == null || profile.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道无可用码流档位, 请先重新同步目录");
        }
        return profile;
    }

    /** 快照图（M4-B2, docs/13 §5.2）：GetSnapshotUri → HTTP 拉图（Basic 兜底）; 失败由调用方回退 ZLM 冷抓 */
    @Override
    public byte[] fetchSnapshot(GbDeviceEntity device, GbDeviceChannelEntity channel) {
        String profile = requireProfile(channel);
        String uri;
        try {
            uri = client.snapshotUri(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device), profile);
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 取快照地址失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
        if (uri == null || uri.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备未返回快照地址(该固件可能不支持 GetSnapshotUri)");
        }
        try {
            return client.fetchSnapshot(uri, device.getOnvifUsername(), plainPassword(device));
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.SNAPSHOT_FAILED,
                    "ONVIF 快照拉取失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /**
     * 录像检索（M4-B3, docs/13 §5.3）：FindRecordings → GetRecordingSearchResults。
     * 失败转 BizException 上抛, 由 OnvifRecordSearchService 走「整窗单段」降级
     * （降级口径要求 Search 不可用 ≠ 设备离线, 文案保持中性）。
     */
    @Override
    public List<com.yujian.vision.onvif.model.OnvifRecordingSegment> searchRecordings(
            GbDeviceEntity device, GbDeviceChannelEntity channel, LocalDateTime start, LocalDateTime end) {
        try {
            return client.findRecordings(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device), start, end);
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 录像检索失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /**
     * 回放拉流地址（M4-B3, docs/13 §5.3）：GetReplayUri 优先 → 失败降级 GetStreamUri 推导
     * 海康 tracks URI（/Streaming/Channels/{n} → /Streaming/tracks/{n}?starttime=&endtime=,
     * mtime 紧凑格式）。产物统一经 OnvifUris.normalizePullUrl 归一（嵌账密+主机重组）。
     */
    @Override
    public String resolveReplayUrl(GbDeviceEntity device, GbDeviceChannelEntity channel,
            LocalDateTime start, LocalDateTime end) {
        String profile = requireProfile(channel);
        // ① GetReplayUri: 成功则把 URI query 里的时间窗替换为请求窗口（部分固件回放 URI
        //    带 starttime=当前时刻, 不替换会从当前时刻播起; 无时间参数则追加）。
        //    非 rtsp:// 等畸形应答（normalizePullUrl 抛 IAE, 评审 B3-P3-5）同样降级 tracks 推导。
        String uri = null;
        try {
            uri = client.replayUri(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device), profile);
        } catch (BizException | OnvifCallException e) {
            log.info("ONVIF GetReplayUri 不可用, 降级 tracks 推导: {}",
                    OnvifErrorText.sanitize(e.getMessage()));
        }
        if (uri != null && !uri.isBlank()) {
            try {
                return OnvifUris.normalizePullUrl(withWindow(uri, start, end),
                        device.getOnvifUsername(), plainPassword(device), device.getIp());
            } catch (IllegalArgumentException e) {
                log.info("ONVIF 回放地址非 RTSP 形态, 降级 tracks 推导: {}", e.getMessage());
            }
        }
        // ② 降级: GetStreamUri 实况地址 → Channels→tracks 推导（海康系私有习惯用法）
        String live;
        try {
            live = client.streamUri(device.getIp(), port(device),
                    device.getOnvifUsername(), plainPassword(device), profile);
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 取回放流地址失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
        if (live == null || live.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "设备未返回流地址, 无法构造回放地址");
        }
        String tracks = deriveTracksUrl(live, start, end);
        if (tracks == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "设备未实现回放取流且流地址非海康 Channels 形态, 无法构造回放地址");
        }
        return OnvifUris.normalizePullUrl(tracks, device.getOnvifUsername(),
                plainPassword(device), device.getIp());
    }

    /** PTZ 服务地址（GetCapabilities XAddr, 缺失回退 device_service） */
    private String ptzUrl(GbDeviceEntity device) {
        return client.ptzServiceUrl(device.getIp(), port(device),
                device.getOnvifUsername(), plainPassword(device));
    }

    /** Imaging 服务地址（聚焦控制, 2026-09-25 现场补齐）: 镜像 ptzUrl 每次经 GetCapabilities 解析 */
    private String imagingUrl(GbDeviceEntity device) {
        return client.imagingServiceUrl(device.getIp(), port(device),
                device.getOnvifUsername(), plainPassword(device));
    }
    /** 聚焦目标 VideoSourceToken（目录同步时已落库 onvif_source_token 列） */
    private static String sourceToken(GbDeviceChannelEntity channel) {
        String token = channel.getOnvifSourceToken();
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道无 VideoSourceToken, 请先重新同步目录");
        }
        return token;
    }
    /** Imaging Move 聚焦: x>0=近聚焦/ x<0=远聚焦（ONVIF 聚焦属 Imaging 服务, 目标是 VideoSourceToken） */
    private void imagingFocus(GbDeviceEntity device, GbDeviceChannelEntity channel, double focusX) {
        String src = sourceToken(channel);
        call(device, () -> client.imagingMove(imagingUrl(device), device.getOnvifUsername(),
                plainPassword(device), src, focusX));
    }
    /** 统一外呼包装: OnvifCallException → BizException(清洗文本) */
    private void call(GbDeviceEntity device, Runnable action) {
        try {
            action.run();
        } catch (OnvifCallException e) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "ONVIF 调用失败: " + OnvifErrorText.sanitize(e.getMessage()));
        }
    }

    /** 档位选择: 1=首档(主) / 2=次档(子, 缺则回主); token 不在缓存列表时以列表为准防目录漂移 */
    private static String pickToken(List<OnvifProfile> profiles, String mainToken, int streamProfile) {
        String want = streamProfile == 2 && profiles.size() > 1 ? profiles.get(1).token() : mainToken;
        for (OnvifProfile p : profiles) {
            if (p.token() != null && p.token().equals(want)) {
                return want;
            }
        }
        return mainToken;
    }

    /** onvif_profiles JSON → Profile 列表（解析失败按空处理, 提示重新同步目录） */
    private static List<OnvifProfile> parseProfiles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, OnvifProfile.class));
        } catch (Exception e) {
            log.warn("ONVIF Profiles JSON 解析失败(请重新同步目录): {}", e.getMessage());
            return List.of();
        }
    }

    /** 主/子档位: 像素面积降序（主=首档, 子=次档）; 序列化缓存供播放侧选 profile */
    private static List<OnvifProfile> sortProfiles(List<OnvifProfile> profiles) {
        List<OnvifProfile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparingLong((OnvifProfile p) -> (long) p.width() * p.height()).reversed());
        return sorted;
    }

    private static String toJson(List<OnvifProfile> profiles) {
        try {
            return JSON.writeValueAsString(profiles);
        } catch (Exception e) {
            log.warn("ONVIF Profiles JSON 序列化失败(存空, 不阻塞目录收敛): {}", e.getMessage());
            return "";
        }
    }

    private static int port(GbDeviceEntity device) {
        return device.getPort() != null && device.getPort() > 0 ? device.getPort() : OnvifDeviceDefaults.PORT;
    }

    private String plainPassword(GbDeviceEntity device) {
        String stored = device.getOnvifPassword();
        return stored == null || stored.isBlank() ? "" : aes.decrypt(stored);
    }
}
