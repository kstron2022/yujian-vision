package com.yujian.vision.service.media.entity;

import java.time.LocalDateTime;

/**
 * 通道截图视图对象（M3-B7 阶段2, docs/03 契约）。
 * url/thumbUrl 为同源相对地址（前端同域直接 GET, 免登录靠签名参数, docs/09 风险条目）:
 * /api/channels/{channelDbId}/snapshot?size=full|thumb&sign=..&expire=..
 */
public record SnapshotVo(String url, String thumbUrl, LocalDateTime time, String source) {
}
