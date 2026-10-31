package com.yujian.vision.service.device.onvif;

/**
 * 设备侧受控文本清洗（B1 评审）: 设备/内网 HTTP 服务返回的 Fault 文案在进平台日志、
 * 审计与 API 错误响应前, 压平换行/控制字符——防日志注入与审计记录伪造（docs/13 §2.1 脱敏口径）。
 */
public final class OnvifErrorText {

    private OnvifErrorText() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7F) {
                // 其余控制字符丢弃
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
