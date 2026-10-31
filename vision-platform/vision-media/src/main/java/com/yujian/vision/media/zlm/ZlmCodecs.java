package com.yujian.vision.media.zlm;

import java.util.List;
import java.util.Map;

/**
 * ZLM 编码名规范化（B5 H.265 方案：点播响应/STREAM_READY 携带视频编码，前端选播放路径）。
 *
 * <p>数据源：on_stream_changed / getMediaList 的 tracks[].codec_id_name
 * （如 "H264"、"H265"、"HEVC"）。规范化结果：H264 / H265，无法识别原样返回，空串=未知。</p>
 */
public final class ZlmCodecs {

    private ZlmCodecs() {
    }

    /** codec_id_name 规范化：含 265/HEVC → H265，含 264/AVC → H264，其余原样 */
    public static String normalizeVideoCodec(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String u = name.toUpperCase();
        if (u.contains("265") || u.contains("HEVC")) {
            return "H265";
        }
        if (u.contains("264") || u.contains("AVC")) {
            return "H264";
        }
        return name;
    }

    /**
     * 从 tracks（hook JSON 解析后的 List&lt;Map&gt;）提取视频编码。
     * codec_type=0（视频轨）优先；fallback 只收 codec_type 字段缺失的轨（防误捡音频轨）。
     */
    public static String extractVideoCodec(Object tracks) {
        if (!(tracks instanceof List<?> list)) {
            return "";
        }
        String fallback = "";
        for (Object t : list) {
            if (!(t instanceof Map<?, ?> track)) {
                continue;
            }
            Object nameObj = track.get("codec_id_name");
            if (nameObj == null) {
                continue;
            }
            String normalized = normalizeVideoCodec(String.valueOf(nameObj));
            if (normalized.isEmpty()) {
                continue;
            }
            if ("0".equals(String.valueOf(track.get("codec_type")))) {
                return normalized;
            }
            if (!track.containsKey("codec_type") && fallback.isEmpty()) {
                fallback = normalized;
            }
        }
        return fallback;
    }
}
