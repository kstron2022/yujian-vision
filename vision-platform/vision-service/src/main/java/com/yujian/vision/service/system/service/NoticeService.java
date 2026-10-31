package com.yujian.vision.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yujian.vision.service.media.service.StreamEventPublisher;
import com.yujian.vision.service.system.entity.SysNoticeEntity;
import com.yujian.vision.service.system.entity.SysUserEntity;
import com.yujian.vision.service.system.entity.SysUserMessageEntity;
import com.yujian.vision.service.system.mapper.SysNoticeMapper;
import com.yujian.vision.service.system.mapper.SysUserMapper;
import com.yujian.vision.service.system.mapper.SysUserMessageMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 站内消息中心（docs/03 §2.2, B1-c）：通知落库 + 全用户扇出 + WS NOTICE_NEW 推送（与轮询双通道）。
 * 消息中心属旁路能力, publish 整体 try/catch —— 故障绝不影响主业务流程（离线关停/目录同步等）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 分页上限（docs/03 §2.2: count 默认 10, 上限 100） */
    private static final int MAX_COUNT = 100;

    private final SysNoticeMapper noticeMapper;
    private final SysUserMapper userMapper;
    private final SysUserMessageMapper userMessageMapper;
    private final StreamEventPublisher eventPublisher;

    /** 设备离线通知文案（离线判定任务/注销 两处共用） */
    public static String deviceOfflineContent(String deviceId, String name) {
        return "设备 " + deviceId + (name == null || name.isBlank() ? "" : "（" + name + "）") + " 已离线";
    }

    /**
     * 发布通知：notice 落库 → 全部未删用户逐条扇出 user_message → NOTICE_NEW 推送。
     * 用户量小（v1 单域），循环 insert 即可; 任何异常仅记日志不上抛。
     */
    public void publish(String type, String title, String content, int level, String bizId) {
        try {
            LocalDateTime now = LocalDateTime.now();
            SysNoticeEntity notice = new SysNoticeEntity();
            notice.setType(type);
            notice.setTitle(title);
            notice.setContent(content);
            notice.setLevel(level);
            notice.setBizId(bizId);
            notice.setCreateTime(now);
            noticeMapper.insert(notice);
            List<SysUserEntity> users = userMapper.selectList(
                    new LambdaQueryWrapper<SysUserEntity>().isNull(SysUserEntity::getDeletedTime));
            for (SysUserEntity u : users) {
                SysUserMessageEntity m = new SysUserMessageEntity();
                m.setNoticeId(notice.getId());
                m.setUserId(u.getId());
                m.setCreateTime(now);
                userMessageMapper.insert(m);
            }
            eventPublisher.noticeNew(notice.getId(), type, title, level, now.format(FMT));
        } catch (Exception e) {
            log.error("站内通知发布失败(不影响主业务): type={} title={} bizId={}", type, title, bizId, e);
        }
    }

    /** 用户消息分页（docs/03 §2.2）: unread=true 仅未读/false 仅已读/null 全部 */
    public Map<String, Object> page(Long userId, Boolean unread, int page, int count) {
        int p = Math.max(page, 1);
        int c = Math.min(Math.max(count, 1), MAX_COUNT);
        int offset = (p - 1) * c;
        long total = noticeMapper.countByUser(userId, unread);
        List<Map<String, Object>> list = new ArrayList<>();
        if (total > 0) {
            for (Map<String, Object> row : noticeMapper.pageByUser(userId, unread, offset, c)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("id"));
                item.put("type", row.get("type"));
                item.put("title", row.get("title"));
                item.put("content", row.get("content"));
                item.put("level", row.get("level"));
                item.put("bizId", row.get("biz_id"));
                item.put("createTime", fmtTime(row.get("create_time")));
                item.put("readTime", fmtTime(row.get("read_time")));
                list.add(item);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", p);
        data.put("count", c);
        data.put("list", list);
        return data;
    }

    /** 未读数 */
    public long unreadCount(Long userId) {
        return noticeMapper.countUnreadByUser(userId);
    }

    /** 标记单条已读（read_time 非空不更新, 幂等） */
    public void markRead(Long userId, Long noticeId) {
        updateRead(userId, noticeId);
    }

    /** 全部已读, 返回本次更新条数 */
    public long markAllRead(Long userId) {
        return updateRead(userId, null);
    }

    /** 已读更新: 仅命中 read_time IS NULL 行, noticeId=null 时不限通知（字符串列, 免 MP lambda 缓存依赖） */
    private long updateRead(Long userId, Long noticeId) {
        return userMessageMapper.update(null, new UpdateWrapper<SysUserMessageEntity>()
                .eq("user_id", userId)
                .eq(noticeId != null, "notice_id", noticeId)
                .isNull("read_time")
                .set("read_time", LocalDateTime.now()));
    }

    /** DB DATETIME → API 字符串, null 保持 null */
    private static String fmtTime(Object v) {
        return v instanceof LocalDateTime t ? t.format(FMT) : null;
    }
}
