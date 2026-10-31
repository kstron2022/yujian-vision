package com.yujian.vision.media.zlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ZlmApiClient 的 OkHttp 实现（docs/04 §3）。
 * GET /index/api/{api}?secret=...&params, 应答 {code, data}。
 */
@Component
public class OkHttpZlmApiClient implements ZlmApiClient {

    private static final Logger log = LoggerFactory.getLogger(OkHttpZlmApiClient.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final ZlmNodeRegistry registry;

    private final okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .readTimeout(java.time.Duration.ofSeconds(5))
            .build();

    /**
     * getSnap 专用客户端: 抓帧是同步阻塞接口（ZLM 侧等出帧才回包）, readTimeout 必须大于
     * timeout_sec 上限。其余 ZLM 接口保持 5s 快速失败语义, 不被抓图拖长。
     */
    private final okhttp3.OkHttpClient httpSnap = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .readTimeout(java.time.Duration.ofSeconds(SNAP_MAX_TIMEOUT_SECONDS + 5))
            .build();

    public OkHttpZlmApiClient(ZlmNodeRegistry registry) {
        this.registry = registry;
    }

    /** GET /index/api/{api}?secret=..&params, 返回整个应答 JSON; code!=0 抛 ZLM_API_FAILED */
    private JsonNode callRoot(String serverId, String api, String... kv) {
        ZlmNodeInfo node = registry.resolve(serverId);
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse("http://" + node.hostIp() + ":" + node.httpPort()
                + "/index/api/" + api);
        if (url == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "ZLM 地址非法: " + node.hostIp());
        }
        okhttp3.HttpUrl.Builder ub = url.newBuilder()
                .addQueryParameter("secret", node.secret());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            ub.addQueryParameter(kv[i], kv[i + 1]);
        }
        okhttp3.Request req = new okhttp3.Request.Builder().url(ub.build()).get().build();
        try (okhttp3.Response resp = http.newCall(req).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            JsonNode root = JSON.readTree(text);
            if (resp.code() != 200 || root.path("code").asInt(-1) != 0) {
                throw new BizException(ErrorCode.ZLM_API_FAILED,
                        api + " 失败: HTTP " + resp.code() + " code=" + root.path("code").asInt() + " " + text);
            }
            return root;
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, api + " IO 异常: " + e.getMessage());
        }
    }

    @Override
    public int openRtpServer(String serverId, ZlmOpenRtpServerRequest request) {
        JsonNode root = callRoot(serverId, "openRtpServer",
                "port", String.valueOf(request.port()),
                "enable_tcp", request.enableTcp() ? "1" : "0",
                "stream_mode", request.streamMode(),
                "ssrc", request.ssrc(),
                "stream_id", request.streamId());
        int port = root.path("data").path("port").asInt(root.path("port").asInt(0));
        if (port <= 0) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "openRtpServer 未返回端口: " + root);
        }
        log.info("openRtpServer: server={} stream={} ssrc={} port={}",
                serverId, request.streamId(), request.ssrc(), port);
        return port;
    }

    @Override
    public void closeRtpServer(String serverId, String streamId) {
        callRoot(serverId, "closeRtpServer", "stream_id", streamId);
        log.info("closeRtpServer: server={} stream={}", serverId, streamId);
    }

    @Override
    public void closeStreams(String serverId, String app, String streamId) {
        JsonNode root = callRoot(serverId, "close_streams", "app", app, "stream", streamId);
        // 应答形状随版本有差异（旧版根级 count / 新版 data.msg），恢复清理只关心成功与否, 不解析计数
        log.info("close_streams: server={} app={} stream={} resp={}", serverId, app, streamId, root);
    }

    @Override
    public String getMediaList(String serverId) {
        return callRoot(serverId, "getMediaList").toString();
    }

    @Override
    public java.util.List<ZlmMediaInfo> listMedia(String serverId) {
        JsonNode data = callRoot(serverId, "getMediaList").path("data");
        java.util.List<ZlmMediaInfo> list = new java.util.ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                Integer readers = n.hasNonNull("totalReaderCount") ? n.get("totalReaderCount").asInt() : null;
                // 带宽/累计字节为旧版 ZLM 可能缺失的字段: hasNonNull 守卫, 缺失按 null（docs/09 M2 #8）
                Long bytesSpeed = n.hasNonNull("bytesSpeed") ? n.get("bytesSpeed").asLong() : null;
                Long totalBytes = n.hasNonNull("totalBytes") ? n.get("totalBytes").asLong() : null;
                // 视频编码（B5）: tracks 里 codec_type=0 的 codec_id_name, 缺失为空串
                String videoCodec = ZlmCodecs.normalizeVideoCodec(
                        videoTrackCodecName(n.path("tracks")));
                list.add(new ZlmMediaInfo(n.path("app").asText(null), n.path("stream").asText(null),
                        readers, bytesSpeed, totalBytes, videoCodec));
            }
        }
        return list;
    }

    /** getMediaList 单条目的 tracks（JsonNode 数组）→ 视频轨 codec_id_name（缺失返回空串） */
    private static String videoTrackCodecName(JsonNode tracks) {
        if (!tracks.isArray()) {
            return "";
        }
        String fallback = "";
        for (JsonNode t : tracks) {
            String name = t.path("codec_id_name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            // codec_type: 0=视频轨（ZLM 惯例）, 优先; fallback 只收 codec_type 字段缺失的轨（防误捡音频轨）
            if ("0".equals(t.path("codec_type").asText(""))) {
                return name;
            }
            if (!t.has("codec_type") && fallback.isEmpty()) {
                fallback = name;
            }
        }
        return fallback;
    }

    @Override
    public void startRecord(String serverId, String app, String streamId, int type) {
        // 国标 RTP 流在 ZLM 内无 rtmp schema（默认找不到流 code=-500）, 依次尝试 ts / fmp4
        String err = "";
        for (String schema : new String[]{"ts", "fmp4"}) {
            try {
                callRoot(serverId, "startRecord",
                        "type", String.valueOf(type),
                        "vhost", "__defaultVhost__",
                        "app", app,
                        "stream", streamId,
                        "schema", schema);
                log.info("startRecord: server={} app={} stream={} type={} schema={}",
                        serverId, app, streamId, type, schema);
                return;
            } catch (BizException e) {
                err = e.getMessage();
                log.warn("startRecord schema={} 未成功: {}", schema, err);
            }
        }
        throw new BizException(ErrorCode.ZLM_API_FAILED, "startRecord 失败(ts/fmp4 上均试): " + err);
    }

    /**
     * startSendRtp 参数(实测本 ZLM 版本必需 vhost/dst_url/is_udp): vhost 恒 __defaultVhost__,
     * dst_url=纯 IP(端口单独 dst_port, 不能拼 "ip:port"); passive 版无 dst/is_udp(ZLM 作 TCP server)。
     * isUdp: 主动推流传输(1=UDP/0=TCP client 主动连对端——LiveGBS 等 a=setup:passive 上级必需)。
     * esAudio=true 时追加 type=0(kRtpES 裸流)+only_audio=1+pt=8(PCMA), 对讲设备侧期望裸 G.711A RTP。
     */
    private void callStartSendRtp(String serverId, String app, String streamId, String ssrc,
            String dstIp, Integer dstPort, boolean passive, boolean esAudio, boolean isUdp) {
        java.util.List<String> kv = new java.util.ArrayList<>(java.util.List.of(
                "vhost", "__defaultVhost__",
                "app", app,
                "stream", streamId,
                "ssrc", ssrc));
        if (esAudio) {
            kv.addAll(java.util.List.of("type", "0", "only_audio", "1", "pt", "8"));
        }
        if (!passive) {
            kv.addAll(java.util.List.of(
                    "dst_url", dstIp,
                    "dst_port", String.valueOf(dstPort),
                    "is_udp", isUdp ? "1" : "0"));
        }
        String api = passive ? "startSendRtpPassive" : "startSendRtp";
        JsonNode root = callRoot(serverId, api, kv.toArray(new String[0]));
        // 应答 {code:0, data:{local_port}}(passive 监听端口); 按 code 判定成功, local_port 仅日志
        log.info("{}: server={} app={} stream={} ssrc={} dst={}:{} localPort={}", api, serverId, app, streamId,
                ssrc, passive ? "(listen)" : dstIp, passive ? "-" : dstPort,
                root.path("data").path("local_port").asInt(0));
    }

    @Override
    public void startSendRtp(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort) {
        callStartSendRtp(serverId, app, streamId, ssrc, dstIp, dstPort, false, false, true);
    }

    /** TCP 主动推流（is_udp=0）: ZLM 作 client 连上级被动收流端口, a=setup:passive 场景专用 */
    @Override
    public void startSendRtpTcp(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort) {
        callStartSendRtp(serverId, app, streamId, ssrc, dstIp, dstPort, false, false, false);
    }

    @Override
    public void startSendRtpAudio(String serverId, String app, String streamId, String ssrc, String dstIp, int dstPort) {
        callStartSendRtp(serverId, app, streamId, ssrc, dstIp, dstPort, false, true, true);
    }

    @Override
    public void startSendRtpPassive(String serverId, String app, String streamId, String ssrc) {
        callStartSendRtp(serverId, app, streamId, ssrc, null, 0, true, false, true);
    }


    @Override
    public void stopSendRtp(String serverId, String app, String streamId, String ssrc) {
        java.util.ArrayList<String> kv = new java.util.ArrayList<>(java.util.List.of(
                "vhost", "__defaultVhost__",
                "app", app,
                "stream", streamId));
        if (ssrc != null && !ssrc.isBlank()) {
            kv.addAll(java.util.List.of("ssrc", ssrc));
        }
        callRoot(serverId, "stopSendRtp", kv.toArray(new String[0]));
        log.info("stopSendRtp: server={} app={} stream={} ssrc={}", serverId, app, streamId,
                ssrc == null || ssrc.isBlank() ? "(all)" : ssrc);
    }

    @Override
    public String getVersion(String serverId) {
        JsonNode root = callRoot(serverId, "version");
        JsonNode data = root.path("data");
        // 应答形状随版本有差异: data 数组带 string 字段 / data 对象为 branchName+commitHash+buildTime
        String version;
        if (data.has("string")) {
            version = data.path("string").asText("");
        } else if (data.isObject()) {
            version = (data.path("branchName").asText("") + "/" + data.path("commitHash").asText("")
                    + " (" + data.path("buildTime").asText("") + ")").trim();
        } else {
            version = "";
        }
        log.info("ZLM version: server={} version={}", serverId, version);
        return version;
    }

    @Override
    public String addStreamProxy(String serverId, ZlmAddStreamProxyRequest request) {
        JsonNode root = callRoot(serverId, "addStreamProxy",
                "vhost", "__defaultVhost__",
                "app", request.app(),
                "stream", request.stream(),
                "url", request.url(),
                "enable_rtsp", String.valueOf(request.enableRtsp()),
                "enable_rtmp", String.valueOf(request.enableRtmp()),
                "enable_hls", String.valueOf(request.enableHls()),
                "enable_ts", String.valueOf(request.enableTs()),
                "rtp_type", String.valueOf(request.rtpType()));
        String key = root.path("data").path("key").asText("");
        if (key.isEmpty()) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "addStreamProxy 未返回 key: " + root);
        }
        log.info("addStreamProxy: server={} app={} stream={} key={}",
                serverId, request.app(), request.stream(), key);
        return key;
    }

    @Override
    public void delStreamProxy(String serverId, String key) {
        callRoot(serverId, "delStreamProxy", "key", key);
        log.info("delStreamProxy: server={} key={}", serverId, key);
    }

    @Override
    public ZlmSnap getSnap(String serverId, String url, int timeoutSec, int expireSec) {
        // timeout_sec 收口在 [1,15]s。**不可提前掐断**: 冷抓真帧需 4~5.4s（242 cam1 H265 实测）,
        // 若 timeout_sec 小于出帧耗时, ZLM 回占位图并把它缓存的 snap 播放器留在"占位图卡死"态,
        // 之后同一 url 全部秒回 PNG（实测 3ms/47255B）——表现为永久 1013。expire_sec 是 ZLM 侧
        // 同 url 结果缓存秒数, 与本层轮询无关。
        int t = Math.max(1, Math.min(timeoutSec, SNAP_MAX_TIMEOUT_SECONDS));
        ZlmSnap snap = callBinary(httpSnap, serverId, "getSnap",
                "url", url,
                "timeout_sec", String.valueOf(t),
                "expire_sec", String.valueOf(expireSec));
        log.debug("getSnap: server={} type={} bytes={} timeoutSec={}", serverId, snap.contentType(),
                snap.body() == null ? 0 : snap.body().length, t);
        return snap;
    }

    /**
     * GET /index/api/{api}?secret=..&params → 原始应答字节（getSnap 图片流, 非 JSON）。
     * 与 callRoot 的分工: callRoot 只 .string() 且强制 code==0, 对二进制应答不适用。
     */
    private ZlmSnap callBinary(okhttp3.OkHttpClient client, String serverId, String api, String... kv) {
        ZlmNodeInfo node = registry.resolve(serverId);
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse("http://" + node.hostIp() + ":" + node.httpPort()
                + "/index/api/" + api);
        if (url == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "ZLM 地址非法: " + node.hostIp());
        }
        okhttp3.HttpUrl.Builder ub = url.newBuilder()
                .addQueryParameter("secret", node.secret());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            ub.addQueryParameter(kv[i], kv[i + 1]);
        }
        okhttp3.Request req = new okhttp3.Request.Builder().url(ub.build()).get().build();
        try (okhttp3.Response resp = client.newCall(req).execute()) {
            if (resp.code() != 200) {
                throw new BizException(ErrorCode.ZLM_API_FAILED, api + " HTTP " + resp.code());
            }
            byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
            return new ZlmSnap(resp.header("Content-Type", ""), bytes);
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, api + " IO 异常: " + e.getMessage());
        }
    }
}
