package com.yujian.vision.onvif;

import com.yujian.vision.onvif.message.OnvifMessages;
import com.yujian.vision.onvif.message.OnvifParsers;
import com.yujian.vision.onvif.model.OnvifCallException;
import com.yujian.vision.onvif.model.OnvifDeviceInfo;
import com.yujian.vision.onvif.model.OnvifProfile;
import com.yujian.vision.onvif.model.OnvifVideoSource;
import com.yujian.vision.onvif.soap.OnvifSoapClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;

/**
 * ONVIF 客户端门面（docs/13 §3.1）：目录拉取 = GetVideoSources + GetProfiles 按 SourceToken 归组。
 * 无状态；vision-service 侧按需实例化（超时可控）。
 */
public class OnvifClient {

    private final OnvifSoapClient soap;
    /** 快照 HTTP 拉图客户端（非 SOAP, Basic 认证兜底） */
    private final java.net.http.HttpClient plainHttp = java.net.http.HttpClient.newHttpClient();

    public OnvifClient() {
        this(Duration.ofSeconds(8));
    }

    public OnvifClient(Duration timeout) {
        this.soap = new OnvifSoapClient(timeout);
    }

    /** GetDeviceInformation（测试连接/探活） */
    public OnvifDeviceInfo deviceInfo(String ip, int port, String username, String password) {
        String body = soap.post(endpointUrl(ip, port),
                OnvifMessages.getDeviceInformation(username, password));
        return OnvifParsers.deviceInfo(OnvifSoapClient.newDocument(body));
    }

    /**
     * 目录拉取：VideoSources + Profiles 归组（map 键=VideoSource token）。
     * SourceToken 无匹配 VideoSource 的 Profile（个别厂商不回）归入占位组 ""，调用方决定是否导入。
     */
    public Map<String, List<OnvifProfile>> catalog(String ip, int port, String username, String password) {
        String url = endpointUrl(ip, port);
        // GetVideoSources 是 ONVIF Profile S 的可选操作(cam1 海康实测: 部分固件回
        // ter:ActionNotSupported/500): 拉取失败不致命, 降级为仅按 Profile.SourceToken 归组;
        // 拉取成功则求交集, 非空但不匹配的 token 归占位组 ""(防幽灵通道, 评审项)
        java.util.Set<String> sourceTokens = new java.util.HashSet<>();
        List<OnvifVideoSource> sources;
        boolean vsAvailable;
        try {
            sources = OnvifParsers.videoSources(
                    OnvifSoapClient.newDocument(soap.post(url, OnvifMessages.getVideoSources(username, password))));
            vsAvailable = true;
        } catch (OnvifCallException e) {
            sources = List.of();
            vsAvailable = false;
        }
        for (var vs : sources) {
            sourceTokens.add(vs.token());
        }
        Map<String, List<OnvifProfile>> bySource = new LinkedHashMap<>();
        for (OnvifProfile p : OnvifParsers
                .profiles(OnvifSoapClient.newDocument(soap.post(url, OnvifMessages.getProfiles(username, password))))) {
            String st = p.sourceToken() == null ? "" : p.sourceToken();
            String key = vsAvailable ? (sourceTokens.contains(st) ? st : "")
                    : (st.isEmpty() ? "" : st);
            bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        // 保序补齐无 Profile 的 VideoSource（空 profiles 也保留为一路通道候选）
        for (var vs : sources) {
            bySource.computeIfAbsent(vs.token(), k -> new ArrayList<>());
        }
        return bySource;
    }

    /**
     * GetStreamUri（M4-B2 点播, docs/13 §5.1）：指定档位取流地址（rtsp://, 归一化由调用方做）。
     * 设备未实现/失败抛 OnvifCallException。
     */
    public String streamUri(String ip, int port, String username, String password, String profileToken) {
        String body = soap.post(endpointUrl(ip, port),
                OnvifMessages.getStreamUri(username, password, profileToken));
        return OnvifParsers.mediaUri(OnvifSoapClient.newDocument(body));
    }

    /** GetSnapshotUri（M4-B2 快照, docs/13 §5.2）：指定档位取抓图地址（http:// JPEG） */
    public String snapshotUri(String ip, int port, String username, String password, String profileToken) {
        String body = soap.post(endpointUrl(ip, port),
                OnvifMessages.getSnapshotUri(username, password, profileToken));
        return OnvifParsers.mediaUri(OnvifSoapClient.newDocument(body));
    }

    /** Search 结果轮询参数（M4-B3 评审 B3-P2-3）: 最多 4 拉取 ×400ms 间隔, 超限按失败走降级 */
    private static final int SEARCH_POLL_MAX = 4;
    private static final long SEARCH_POLL_INTERVAL_MS = 400;
    /** 与 OnvifMessages.getRecordingSearchResults 的 MaxResults 一致（分页续拉挂账, 截断时告警） */
    private static final int SEARCH_MAX_RESULTS = 200;

    /**
     * FindRecordings + GetRecordingSearchResults（M4-B3 回放检索, docs/13 §5.3）：
     * 发起搜索→按 SearchToken 轮询拉结果至 SearchState=Completed。两跳都发 device_service
     * 端点——cam1 V5.5.104 实测 Search 服务在 device 端点路由可达。
     * SearchToken 空（未实现/Fault）或超时未 Completed 抛 OnvifCallException——半程结果
     * 不当作权威（调用方走整窗单段降级, 而非误判「无录像」）。
     */
    public List<com.yujian.vision.onvif.model.OnvifRecordingSegment> findRecordings(
            String ip, int port, String username, String password,
            java.time.LocalDateTime from, java.time.LocalDateTime until) {
        String body = soap.post(endpointUrl(ip, port), OnvifMessages.findRecordings(username, password,
                OnvifTime.toXsDateTime(from), OnvifTime.toXsDateTime(until)));
        String token = OnvifParsers.searchToken(OnvifSoapClient.newDocument(body));
        if (token == null || token.isBlank()) {
            throw new OnvifCallException("设备未返回录像搜索句柄(该固件可能不支持 Search 服务)", null);
        }
        List<com.yujian.vision.onvif.model.OnvifRecordingSegment> segments = List.of();
        String state = "";
        for (int i = 0; i < SEARCH_POLL_MAX; i++) {
            String resBody = soap.post(endpointUrl(ip, port),
                    OnvifMessages.getRecordingSearchResults(username, password, token));
            Document doc = OnvifSoapClient.newDocument(resBody);
            segments = OnvifParsers.recordingSegments(doc);
            state = OnvifParsers.searchState(doc);
            if ("Completed".equalsIgnoreCase(state)) {
                if (segments.size() >= SEARCH_MAX_RESULTS) {
                    System.getLogger(OnvifClient.class.getName()).log(System.Logger.Level.WARNING,
                            "录像检索结果达到单次上限, 可能截断(分页挂账): count={0}", segments.size());
                }
                return segments;
            }
            try {
                Thread.sleep(SEARCH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new OnvifCallException("录像搜索未在时限内完成(state=" + state + ")", null);
    }

    /**
     * GetReplayUri（M4-B3 回放取流）：地址经 GetCapabilities Replay XAddr 解析（缺失回退
     * device_service）。未实现/无录像（cam1 实测 ter:NoRecording）抛 OnvifCallException,
     * 调用方降级为 GetStreamUri 推导海康 tracks URI。
     */
    public String replayUri(String ip, int port, String username, String password, String profileToken) {
        String url = replayServiceUrl(ip, port, username, password);
        String body = soap.post(url, OnvifMessages.getReplayUri(username, password, profileToken));
        String uri = OnvifParsers.replayUri(OnvifSoapClient.newDocument(body));
        if (uri == null || uri.isBlank()) {
            throw new OnvifCallException("设备未返回回放流地址(该固件可能不支持 Replay 服务)", null);
        }
        return uri;
    }

    /** Replay 服务地址: GetCapabilities XAddr, 缺失回退 device_service（与 PTZ 同策略） */
    public String replayServiceUrl(String ip, int port, String username, String password) {
        String body = soap.post(endpointUrl(ip, port),
                OnvifMessages.getCapabilities(username, password));
        String xaddr = OnvifParsers.replayXAddr(OnvifSoapClient.newDocument(body));
        return xaddr.isEmpty() ? endpointUrl(ip, port) : xaddr;
    }

    /**
     * PTZ 服务地址（M4-B2 云台, docs/13 §3.2）：GetCapabilities 取 XAddr,
     * 未实现/缺失时回退 device_service（海康等单端点固件的常态）。
     */
    public String ptzServiceUrl(String ip, int port, String username, String password) {
        String body = soap.post(endpointUrl(ip, port),
                OnvifMessages.getCapabilities(username, password));
        String xaddr = OnvifParsers.ptzXAddr(OnvifSoapClient.newDocument(body));
        return xaddr.isEmpty() ? endpointUrl(ip, port) : xaddr;
    }

    /** ContinuousMove：panX/panY/zoom ∈ [-1,1] */
    public void ptzMove(String serviceUrl, String username, String password,
            String profileToken, double panX, double panY, double zoom) {
        soap.postStrict(serviceUrl, OnvifMessages.continuousMove(username, password, profileToken, panX, panY, zoom));
    }

    /** Stop（方向/变倍全停） */
    public void ptzStop(String serviceUrl, String username, String password, String profileToken) {
        soap.postStrict(serviceUrl, OnvifMessages.ptzStop(username, password, profileToken));
    }

    /** 预置位操作：operation = SetPreset / GotoPreset / RemovePreset, presetToken=GB 预置位号字符串 */
    public void ptzPreset(String serviceUrl, String username, String password,
            String profileToken, String operation, String presetToken) {
        soap.postStrict(serviceUrl, OnvifMessages.ptzPreset(username, password, profileToken, operation, presetToken));
    }

    /** GetPresets 预置位检索（2026-09-25 现场补齐）: 成功应答解析 token/name 列表 */
    public java.util.List<com.yujian.vision.onvif.model.OnvifPreset> ptzPresets(String serviceUrl,
            String username, String password, String profileToken) {
        String body = soap.postStrict(serviceUrl,
                OnvifMessages.getPresets(username, password, profileToken));
        return OnvifParsers.presets(OnvifSoapClient.newDocument(body));
    }

    /** Imaging 服务地址（聚焦控制）: GetCapabilities 取 XAddr, 缺失回退 device_service（与 PTZ 同策略） */
    public String imagingServiceUrl(String ip, int port, String username, String password) {
        String body = soap.post(endpointUrl(ip, port), OnvifMessages.getCapabilities(username, password));
        String xaddr = OnvifParsers.imagingXAddr(OnvifSoapClient.newDocument(body));
        return xaddr.isEmpty() ? endpointUrl(ip, port) : xaddr;
    }

    /** Imaging Move 聚焦（focusX ∈ [-1,1], 正=近/负=远）; 停止走 imagingStop */
    public void imagingMove(String serviceUrl, String username, String password,
            String videoSourceToken, double focusX) {
        soap.postStrict(serviceUrl, OnvifMessages.imagingMove(username, password, videoSourceToken, focusX));
    }

    /** Imaging Stop 聚焦停（PTZ Stop 不停聚焦——松开聚焦键需另发 Imaging Stop） */
    public void imagingStop(String serviceUrl, String username, String password, String videoSourceToken) {
        soap.postStrict(serviceUrl, OnvifMessages.imagingStop(username, password, videoSourceToken));
    }

    private static String endpointUrl(String ip, int port) {
        return new com.yujian.vision.onvif.model.OnvifEndpoint(ip, port).deviceServiceUrl();
    }

    /**
     * 快照图拉取（M4-B2, docs/13 §5.2）：GetSnapshotUri 返回的 HTTP JPEG 地址平台代取。
     * 先无凭据直取（部分固件 URI 内嵌凭据或免认证）, 401 → Basic 重试;
     * Digest-only 固件不支持 → 调用方回退 ZLM 冷抓路径（ONVIF 点播链路兜底）。
     */
    public byte[] fetchSnapshot(String uri, String username, String password) {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(uri))
                .timeout(java.time.Duration.ofSeconds(8))
                .GET()
                .build();
        java.net.http.HttpResponse<byte[]> resp;
        try {
            resp = plainHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 401) {
                String basic = java.util.Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(uri))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .header("Authorization", "Basic " + basic)
                        .GET()
                        .build();
                resp = plainHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            }
        } catch (IOException e) {
            throw new OnvifCallException("快照拉取失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OnvifCallException("快照拉取被中断", e);
        }
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
            throw new OnvifCallException("快照拉取失败: HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
