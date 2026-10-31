package com.yujian.vision.service.alarm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import com.yujian.vision.service.alarm.entity.GbAlarmEvent;
import com.yujian.vision.service.alarm.mapper.GbAlarmEventMapper;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.system.service.NoticeService;
import com.yujian.vision.sip.transport.SipEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 报警事件服务（M3-B4）：设备/下级平台报警归一化入库 + 分页查询 + 处理。
 * 来源（SipEventRouter 三路汇入）：MESSAGE Notify/Alarm（2016 设备主动上报）、
 * MESSAGE Response/Alarm、SIP NOTIFY/Alarm（订阅推送）。允许设备未订阅直接上报
 * （用户决策）, 来源校验只认「From 设备档案 + 报警源须为该设备自身或其通道」。
 * 通知/WS 为旁路能力: publish/alarmEvent 整体 try/catch, 故障不影响入库主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    /** 报警时间国标格式（GB/T 28181 yyyy-MM-ddTHH:mm:ss）与厂商空格变体 */
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter SPACE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 报警描述入库截断长度（DDL description VARCHAR(512)） */
    private static final int DESC_MAX = 512;

    private final GbAlarmEventMapper alarmMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final StreamEventPublisher eventPublisher;
    private final NoticeService noticeService;
    private final com.yujian.vision.service.cascade.service.CascadeAlarmService cascadeAlarmService;

    /**
     * 报警入库（路由见 SipEventRouter）：
     * ① From 设备须有档案; ② 报警源（报文 DeviceID, 缺省回退 From）须为该设备自身或其在册通道
     * （单通道设备 DeviceID==通道编码同值时归一到同码通道归属, 供级联共享过滤/虚拟编码使用）;
     * ③ 字段归一化（级别缺省 4=四级最低, 时间缺省/非法取当前, 描述截断 512）→ 落库;
     * ④ WS ALARM + 站内通知 + 上级订阅制上报（三者均旁路, 失败仅记日志不影响入库）。
     */
    public void ingest(SipEvent event, ManScdpMessage message) {
        String fromUser = event.getFromUser();
        if (fromUser == null || fromUser.isBlank()) {
            log.warn("报警来源缺失 From, 丢弃");
            return;
        }
        GbDeviceEntity device = selectDevice(fromUser);
        if (device == null) {
            log.warn("报警来源无设备档案, 丢弃: from={}", fromUser);
            return;
        }
        String source = message.getDeviceId() != null && !message.getDeviceId().isBlank()
                ? message.getDeviceId() : fromUser;
        Long channelDbId = null;
        String channelId = null;
        if (!source.equals(device.getDeviceId())) {
            GbDeviceChannelEntity channel = selectChannel(device.getId(), source);
            if (channel == null) {
                log.warn("报警源非本设备/其通道, 丢弃: device={} source={}", device.getDeviceId(), source);
                return;
            }
            channelDbId = channel.getId();
            channelId = source;
        } else {
            // 单通道设备 DeviceID 与通道编码同值(GB28181 常态): 报警源同时具备通道归属,
            // 带上 channelDbId 供级联共享过滤/虚拟编码使用(M3-B4 阶段2)
            GbDeviceChannelEntity self = selectChannel(device.getId(), device.getDeviceId());
            if (self != null) {
                channelDbId = self.getId();
                channelId = self.getChannelId();
            }
        }
        Integer priority = message.getAlarmPriority() != null ? message.getAlarmPriority() : 4;
        LocalDateTime alarmTime = parseAlarmTime(message.getAlarmTime());
        GbAlarmEvent entity = new GbAlarmEvent();
        entity.setDeviceDbId(device.getId());
        entity.setChannelDbId(channelDbId);
        entity.setDeviceId(device.getDeviceId());
        entity.setChannelId(channelId);
        entity.setAlarmMethod(message.getAlarmMethod());
        entity.setAlarmType(message.getAlarmType());
        entity.setAlarmPriority(priority);
        entity.setAlarmTime(alarmTime);
        entity.setDescription(truncate(message.getAlarmDescription()));
        entity.setHandled(0);
        entity.setCreateTime(LocalDateTime.now());
        alarmMapper.insert(entity);
        log.info("报警入库: device={} channel={} type={} priority={}",
                device.getDeviceId(), channelId, message.getAlarmType(), priority);
        notifySideChannels(device.getDeviceId(), channelId, message.getAlarmType(), priority, alarmTime);
        // M3-B4 阶段2: 上级订阅制上报（共享过滤+虚拟编码在服务内完成, 旁路不回抛异常）
        cascadeAlarmService.onAlarm(entity);
    }

    /** WS 推送 + 站内通知（旁路原则: 任一失败不影响报警入库主流程） */
    private void notifySideChannels(String deviceId, String channelId, Integer alarmType,
            Integer priority, LocalDateTime alarmTime) {
        try {
            eventPublisher.alarmEvent(deviceId, channelId,
                    alarmType == null ? 0 : alarmType, priority, alarmTime.format(ISO_FMT));
        } catch (Exception e) {
            log.error("报警 WS 推送失败(不影响入库): device={}", deviceId, e);
        }
        try {
            // 级别映射: 国标 1一级(最重)~4四级(最轻) → 站内 1提示/2重要（clamp 到 1/2, 3紧急留给系统级）:
            // 一二级 → 2(重要), 三四级 → 1(提示)
            int level = priority <= 2 ? 2 : 1;
            String content = "设备 " + deviceId
                    + (channelId == null ? "" : " 通道 " + channelId)
                    + " 上报报警: 类型" + (alarmType == null ? "未知" : alarmType)
                    + ", 级别" + priority + ", 时间 " + alarmTime.format(SPACE_FMT);
            noticeService.publish("ALARM", "设备报警", content, level, deviceId);
        } catch (Exception e) {
            log.error("报警站内通知失败(不影响入库): device={}", deviceId, e);
        }
    }

    /** 报警分页（docs/03 报警中心）: deviceId 精确 + keyword 模糊(描述/通道/设备编码) + handled + alarm_time 区间 */
    public IPage<GbAlarmEvent> page(String deviceId, String keyword, Integer handled,
            String start, String end, int page, int count) {
        LocalDateTime startAt = parseRange(start, "start");
        LocalDateTime endAt = parseRange(end, "end");
        return alarmMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<GbAlarmEvent>()
                        .eq(StringUtils.hasText(deviceId), GbAlarmEvent::getDeviceId, deviceId)
                        .eq(handled != null, GbAlarmEvent::getHandled, handled)
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(GbAlarmEvent::getDescription, keyword)
                                .or().like(GbAlarmEvent::getChannelId, keyword)
                                .or().like(GbAlarmEvent::getDeviceId, keyword))
                        .ge(startAt != null, GbAlarmEvent::getAlarmTime, startAt)
                        .le(endAt != null, GbAlarmEvent::getAlarmTime, endAt)
                        .orderByDesc(GbAlarmEvent::getAlarmTime)
                        .orderByDesc(GbAlarmEvent::getId));
    }

    /** 报警处理: 标记已处理（处理人/时间落库）; 不存在或已处理 → 400（幂等护栏） */
    public void handle(Long id, String handleUser) {
        GbAlarmEvent exist = alarmMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报警不存在: " + id);
        }
        if (exist.getHandled() != null && exist.getHandled() == 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报警已处理: " + id);
        }
        GbAlarmEvent update = new GbAlarmEvent();
        update.setId(id);
        update.setHandled(1);
        update.setHandleTime(LocalDateTime.now());
        update.setHandleUser(handleUser);
        alarmMapper.updateById(update);
        log.info("报警处理: id={} user={}", id, handleUser);
    }

    // ---------------------------------------------------------------- 内部

    private GbDeviceEntity selectDevice(String deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<GbDeviceEntity>()
                .eq(GbDeviceEntity::getDeviceId, deviceId)
                .isNull(GbDeviceEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    private GbDeviceChannelEntity selectChannel(Long deviceDbId, String channelId) {
        return channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getDeviceDbId, deviceDbId)
                .eq(GbDeviceChannelEntity::getChannelId, channelId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
    }

    /** AlarmTime 解析: 国标 ISO 优先, 空格变体兼容, 缺省/非法回退当前时刻（归一化不拒收） */
    private static LocalDateTime parseAlarmTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return LocalDateTime.now();
        }
        String v = raw.trim();
        try {
            return LocalDateTime.parse(v, ISO_FMT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(v, SPACE_FMT);
            } catch (DateTimeParseException e2) {
                log.warn("AlarmTime 非法, 回退当前时刻: {}", raw);
                return LocalDateTime.now();
            }
        }
    }

    /** 查询区间参数解析（yyyy-MM-dd HH:mm:ss）, 空=不过滤, 非法=400 */
    private static LocalDateTime parseRange(String raw, String field) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), SPACE_FMT);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "时间格式非法(须 yyyy-MM-dd HH:mm:ss): " + field);
        }
    }

    private static String truncate(String description) {
        if (description == null) {
            return null;
        }
        String v = description.trim();
        return v.length() > DESC_MAX ? v.substring(0, DESC_MAX) : v;
    }
}
