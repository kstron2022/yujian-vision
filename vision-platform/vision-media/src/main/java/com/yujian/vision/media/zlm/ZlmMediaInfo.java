package com.yujian.vision.media.zlm;

/**
 * ZLM getMediaList 单条流信息（docs/04 §1）。
 *
 * <p>bytesSpeed/totalBytes 为旧版 ZLM 可能缺失的字段, 解析侧以 hasNonNull 守卫, 缺失为 null
 * （docs/09 M2 #8 带宽指标数据源）。videoCodec 为该流视频编码规范化名（H264/H265, B5 多协议）,
 * 来自 tracks 视频轨 codec_id_name, 旧版/异常缺失为空串。</p>
 *
 * @param app              流所属应用（国标收流固定 rtp）
 * @param stream           流 ID
 * @param totalReaderCount 总读者数（旧版可能缺失, null=未知）
 * @param bytesSpeed       实时收发速率 B/s（该 schema 条目口径, 旧版可能缺失）
 * @param totalBytes       累计收发字节数（旧版可能缺失）
 * @param videoCodec       视频编码规范化名（H264/H265, B5 复用点播选路用）
 */
public record ZlmMediaInfo(String app, String stream, Integer totalReaderCount,
        Long bytesSpeed, Long totalBytes, String videoCodec) {
}
