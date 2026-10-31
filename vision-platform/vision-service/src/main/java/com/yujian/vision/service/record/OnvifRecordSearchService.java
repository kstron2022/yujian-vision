package com.yujian.vision.service.record;

import com.yujian.vision.onvif.model.OnvifRecordingSegment;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.spi.DeviceAccessRegistry;
import com.yujian.vision.service.device.spi.DeviceProtocol;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ONVIF 录像检索（M4-B3, docs/13 §5.3）：Search Service 查询 → RecordSegments 收敛复用。
 *
 * <p>降级口径（用户拍板）：Search 服务调用失败（不支持/超时/网络）→ 返回<b>整窗单段</b>
 * [start,end]，前端时间轴呈现一整块可点击区间，回放按时段起流观看——「检索不到明细 ≠
 * 不能回放」，与 GB 侧「沉默设备 1010」不同，ONVIF 起流地址不依赖检索结果。
 * Search 正常完成但结果为空 → 照实返回空（设备说没有录像, 不猜测）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnvifRecordSearchService {

    private final DeviceAccessRegistry accessRegistry;

    /** 查询结果 + 降级标记（评审 B3-P2-4: 降级产物不写结果缓存, 防 60s 内重试被合成段挡住） */
    public record SearchOutcome(List<RecordSegment> segments, boolean degraded) {
    }

    public List<RecordSegment> query(GbDeviceEntity device, GbDeviceChannelEntity channel,
            LocalDateTime start, LocalDateTime end) {
        return queryOutcome(device, channel, start, end).segments();
    }

    public SearchOutcome queryOutcome(GbDeviceEntity device, GbDeviceChannelEntity channel,
            LocalDateTime start, LocalDateTime end) {
        List<OnvifRecordingSegment> segments;
        try {
            segments = accessRegistry.of(DeviceProtocol.ONVIF)
                    .searchRecordings(device, channel, start, end);
        } catch (RuntimeException e) {
            // 降级: 整窗单段（含 OnvifCallException→BizException / SOAP 超时等一切检索面失败;
            // 不吞 Error）。回落段 recordId 留空, 名称取通道名。
            log.warn("ONVIF 录像检索不可用, 整窗单段降级: device={} channel={} 范围[{} ~ {}] err={}",
                    device.getDeviceId(), channel.getChannelId(), start, end, e.getMessage());
            return new SearchOutcome(List.of(new RecordSegment(channel.getChannelId(), "",
                    channel.getName() == null ? "" : channel.getName(), "time", start, end)), true);
        }
        List<RecordSegment> raw = segments.stream()
                .map(s -> new RecordSegment(channel.getChannelId(), s.recordingToken(),
                        s.sourceName() == null ? "" : s.sourceName(), "time", s.start(), s.end()))
                .toList();
        return new SearchOutcome(RecordSegments.normalize(raw, start, end), false);
    }
}
