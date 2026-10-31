package com.yujian.vision.service.device.rtsp;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.AesCipher;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 拉流代理静态支撑（M5-B1, docs/14 §5）：URL/传输协议校验与归一 + 批量导入行解析。
 * 纯静态无状态（单测免夹具）。明文 URL 上限 512（AES-GCM 密文膨胀后仍在列宽 768 内）。
 */
public final class RtspSourceSupport {

    /** 明文 URL 上限（密文膨胀 ≈×1.37+40, 512 → 密文 <768 列宽内） */
    static final int URL_MAX_LEN = 512;

    /** 批量导入单次上限（textarea 模式无文件流, 防极端提交） */
    static final int BATCH_MAX_LINES = 200;

    private RtspSourceSupport() {
    }

    /** 校验并归一 rtsp:// 地址（trim; scheme+host+不含空格; 长度≤512; 拒绝已加密形态） */
    static String validateSourceUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流地址不能为空");
        }
        String url = raw.trim();
        if (AesCipher.isEncrypted(url)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流地址不应为已加密形态");
        }
        if (url.contains(" ")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流地址不能含空格（请转义为 %20）");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // 安全(评审): URISyntaxException.getMessage() 含完整输入串(可能带凭证), 只报位置不回显原文
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "拉流地址非法(第 " + e.getIndex() + " 个字符附近)");
        }
        if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            // 安全(评审): 错误消息不回显原始 URL——明文会经审计 detail/应用日志落库
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "拉流地址须为 rtsp://host[:port]/path 形式");
        }
        rejectDangerousHost(uri.getHost());
        if (url.length() > URL_MAX_LEN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "拉流地址过长（明文≤" + URL_MAX_LEN + "）");
        }
        return url;
    }

    /** 校验并归一传输协议（缺省 tcp; 仅 tcp/udp; 返回小写归一值） */
    static String validateTransport(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "tcp";
        }
        String t = raw.trim().toLowerCase();
        if (!"tcp".equals(t) && !"udp".equals(t)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "传输协议仅支持 tcp/udp: " + raw);
        }
        return t;
    }

    /**
     * 批量导入行解析（docs/14 §5 两种行格式）: 纯 URL（自动命名）或「名称,URL」。
     * 按第一个逗号切分（名称不含逗号; URL 段原样保留, 含逗号的 query 不受影响）;
     * 跳过空行与 # 开头注释行。行内容不做 URL 校验（校验在建档循环逐行做, 失败行进 failed 清单）。
     */
    static List<ParsedLine> parseLines(String text) {
        List<ParsedLine> lines = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return lines;
        }
        int lineNo = 0;
        for (String raw : text.split("\r?\n")) {
            lineNo++;
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int comma = s.indexOf(',');
            if (comma <= 0) {
                lines.add(new ParsedLine(lineNo, null, s));
            } else {
                lines.add(new ParsedLine(lineNo, s.substring(0, comma).trim(), s.substring(comma + 1).trim()));
            }
            if (lines.size() >= BATCH_MAX_LINES) {
                break;
            }
        }
        return lines;
    }

    /** ParsedLine: 批量导入解析行（name 可空=自动命名） */
    record ParsedLine(int lineNo, String name, String url) {
    }


    /**
     * 安全(评审 P2): IP 字面量形式拒绝回环/链路本地(含云元数据 169.254)/组播/保留网段——
     * 防平台探活与 ZLM 拉流被用作内网测绘 oracle。域名与 IPv6 字面量不做网段判定
     * (摄像头可为 hostname, 平台本身即内网视频接入系统, device:add 已是信任边界; docs/14 挂账)。
     */
    static void rejectDangerousHost(String host) {
        if (host == null) {
            return;
        }
        int p1 = host.indexOf('.');
        if (p1 <= 0) {
            return;
        }
        int p2 = host.indexOf('.', p1 + 1);
        int p3 = p2 < 0 ? -1 : host.indexOf('.', p2 + 1);
        if (p2 <= 0 || p3 <= 0 || p3 == host.length() - 1 || host.indexOf('.', p3 + 1) >= 0) {
            return; // 非恰好四段的 IPv4 字面量形态
        }
        int[] pos = {0, p1, p2, p3};
        int first = 0;
        int second = 0;
        for (int i = 0; i < 4; i++) {
            int start = i == 0 ? 0 : pos[i] + 1; // 跳过分隔点
            String seg = host.substring(start, i < 3 ? pos[i + 1] : host.length());
            if (seg.isEmpty() || seg.length() > 3) {
                return;
            }
            for (int j = 0; j < seg.length(); j++) {
                char c = seg.charAt(j);
                if (c < '0' || c > '9') {
                    return; // 含非数字字符 → 非字面量
                }
            }
            if (i == 0) {
                first = Integer.parseInt(seg);
            }
            if (i == 1) {
                second = Integer.parseInt(seg);
            }
        }
        if (first == 0 || first == 127 || first >= 224 || (first == 169 && second == 254)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "拉流地址不允许为回环/链路本地/组播/保留网段(IP 字面量)");
        }
    }
}
