package com.yujian.vision.media.rtsp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * RTSP 轻量探活客户端（M5-B1, docs/14 §6）：TCP 连接 + OPTIONS 请求交换即断,
 * <b>不拉流不耗带宽</b>。任何以 "RTSP/" 开头的应答状态行（200/401/404…）都判源可达——
 * 探活语义是「源网络与服务可达性」, 不校验凭证(401 同样证明服务在应答);
 * 凭证错误在真实拉流时暴露, 属业务失败而非离线。
 * 纯 JDK Socket 无 Spring 依赖, 单测可直接构造。
 */
@Slf4j
public class RtspProbeClient {

    /** RTSP 默认端口（URL 未显式携带时） */
    public static final int DEFAULT_PORT = 554;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public RtspProbeClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 探测结果: ok=源可达; message=失败原因（不可达/超时/非法 URL） */
    public record ProbeResult(boolean ok, String message) {
    }

    /**
     * 探测 rtsp://[user:pass@]host[:port][/path]: 发 OPTIONS, 读到 RTSP/ 状态行即成功。
     * CSeq 等头不完整无妨——探测即断, 不进入会话态。
     */
    public ProbeResult probe(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return new ProbeResult(false, "URL 非法: " + e.getMessage());
        }
        if (!"rtsp".equalsIgnoreCase(uri.getScheme())) {
            return new ProbeResult(false, "仅支持 rtsp:// 地址");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return new ProbeResult(false, "URL 缺少主机地址");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            String target = buildRequestTarget(uri);
            byte[] req = ("OPTIONS " + target + " RTSP/1.0\r\n"
                    + "CSeq: 1\r\n"
                    + "User-Agent: YuJianVision-Probe\r\n"
                    + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            // OutputStream 不用 try-with-resources: 关闭 socket 关联流会级联关闭整个 socket
            // (JDK 语义), 随后 getInputStream() 必抛 "Socket is closed"(242 联调实测)——
            // socket 生命周期由外层 try-with-resources 统一管理, 流不单独关。
            OutputStream out = socket.getOutputStream();
            out.write(req);
            out.flush();
            String statusLine = readStatusLine(socket.getInputStream());
            if (statusLine != null && statusLine.startsWith("RTSP/")) {
                log.debug("RTSP 探活可达: host={} port={} status={}", host, port, statusLine.trim());
                return new ProbeResult(true, statusLine.trim());
            }
            return new ProbeResult(false,
                    "非 RTSP 应答: " + (statusLine == null ? "连接即断" : statusLine));
        } catch (Exception e) {
            log.debug("RTSP 探活失败: host={} port={} err={}", host, port, e.getMessage());
            return new ProbeResult(false, e.getMessage());
        }
    }

    /** 请求目标带路径与 query（有的源按路径区分流, 探活尽量贴近真实拉流目标） */
    private static String buildRequestTarget(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        return path;
    }

    /** 读一行状态行（限长防恶意源刷流） */
    private static String readStatusLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 128) {
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
