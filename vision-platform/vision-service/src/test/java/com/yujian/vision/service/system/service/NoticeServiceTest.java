package com.yujian.vision.service.system.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 消息中心服务单元测试（docs/03 §2.2, B1-c）：纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Mock
    private SysNoticeMapper noticeMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysUserMessageMapper userMessageMapper;

    @Mock
    private StreamEventPublisher eventPublisher;

    private NoticeService service;

    @BeforeEach
    void setUp() {
        service = new NoticeService(noticeMapper, userMapper, userMessageMapper, eventPublisher);
    }

    @Test
    @DisplayName("publish: notice 落库 + 每用户一条扇出 + noticeNew 一次")
    void publishFanoutAndEvent() {
        stubInsertAssignsId(66L);
        when(userMapper.selectList(any())).thenReturn(List.of(user(1L), user(2L)));

        service.publish("DEVICE_OFFLINE", "设备离线", "设备 x 已离线", 1, "dev-1");

        ArgumentCaptor<SysNoticeEntity> noticeCap = ArgumentCaptor.forClass(SysNoticeEntity.class);
        verify(noticeMapper, times(1)).insert(noticeCap.capture());
        SysNoticeEntity n = noticeCap.getValue();
        assertEquals("DEVICE_OFFLINE", n.getType());
        assertEquals("设备离线", n.getTitle());
        assertEquals("设备 x 已离线", n.getContent());
        assertEquals(1, n.getLevel());
        assertEquals("dev-1", n.getBizId());
        assertNotNull(n.getCreateTime());

        ArgumentCaptor<SysUserMessageEntity> msgCap = ArgumentCaptor.forClass(SysUserMessageEntity.class);
        verify(userMessageMapper, times(2)).insert(msgCap.capture());
        List<SysUserMessageEntity> msgs = msgCap.getAllValues();
        assertEquals(List.of(1L, 2L), msgs.stream().map(SysUserMessageEntity::getUserId).toList());
        for (SysUserMessageEntity m : msgs) {
            assertEquals(66L, m.getNoticeId());
            assertNotNull(m.getCreateTime());
        }

        ArgumentCaptor<String> timeCap = ArgumentCaptor.forClass(String.class);
        verify(eventPublisher, times(1)).noticeNew(eq(66L), eq("DEVICE_OFFLINE"), eq("设备离线"),
                eq(1), timeCap.capture());
        // createTime 以 yyyy-MM-dd HH:mm:ss 推送（WS/轮询双通道同一时间格式）
        assertDoesNotThrow(() -> LocalDateTime.parse(timeCap.getValue(), FMT));
    }

    @Test
    @DisplayName("publish: 扇出 insert 异常 → 吞掉不上抛, 不发 NOTICE_NEW")
    void publishSwallowsFanoutFailure() {
        stubInsertAssignsId(66L);
        when(userMapper.selectList(any())).thenReturn(List.of(user(1L)));
        doThrow(new RuntimeException("db down")).when(userMessageMapper).insert(any(SysUserMessageEntity.class));

        assertDoesNotThrow(() ->
                service.publish("CATALOG_SYNC_FAIL", "目录同步失败", "设备 x 目录查询无应答", 2, "dev-2"));

        verify(eventPublisher, never()).noticeNew(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("markRead/markAllRead: 仅命中 read_time IS NULL 行（幂等）")
    void markReadOnlyTouchesUnread() {
        when(userMessageMapper.update(isNull(), any())).thenReturn(1);

        service.markRead(9L, 66L);
        service.markAllRead(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysUserMessageEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMessageMapper, times(2)).update(isNull(), captor.capture());
        List<Wrapper<SysUserMessageEntity>> wrappers = captor.getAllValues();
        String markOne = ((UpdateWrapper<SysUserMessageEntity>) wrappers.get(0)).getSqlSegment().toLowerCase();
        String markAll = ((UpdateWrapper<SysUserMessageEntity>) wrappers.get(1)).getSqlSegment().toLowerCase();

        assertTrue(markOne.contains("user_id ="));
        assertTrue(markOne.contains("notice_id ="));
        assertTrue(markOne.contains("read_time is null"));

        assertTrue(markAll.contains("user_id ="));
        assertFalse(markAll.contains("notice_id ="));
        assertTrue(markAll.contains("read_time is null"));
    }

    @Test
    @DisplayName("page: 下划线键转驼峰 + 时间格式化 + readTime null 保持 null")
    void pageConvertsRowKeysToCamelCase() {
        when(noticeMapper.countByUser(9L, Boolean.TRUE)).thenReturn(1L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("type", "DEVICE_OFFLINE");
        row.put("title", "设备离线");
        row.put("content", "设备 x 已离线");
        row.put("level", 1);
        row.put("biz_id", "dev-1");
        row.put("create_time", LocalDateTime.of(2026, 1, 1, 8, 30, 0));
        row.put("read_time", null);
        when(noticeMapper.pageByUser(9L, Boolean.TRUE, 0, 10)).thenReturn(List.of(row));

        Map<String, Object> data = service.page(9L, Boolean.TRUE, 1, 10);

        assertEquals(1L, data.get("total"));
        assertEquals(1, data.get("page"));
        assertEquals(10, data.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(1, list.size());
        Map<String, Object> item = list.get(0);
        assertEquals(7L, item.get("id"));
        assertEquals("DEVICE_OFFLINE", item.get("type"));
        assertEquals("dev-1", item.get("bizId"));
        assertEquals("2026-01-01 08:30:00", item.get("createTime"));
        assertNull(item.get("readTime"));
    }

    @Test
    @DisplayName("page: page<1 纠偏, count 收敛到上限 100, total=0 不再查列表")
    void pageClampsPagingParams() {
        when(noticeMapper.countByUser(9L, null)).thenReturn(0L);

        Map<String, Object> page = service.page(9L, null, 0, 500);

        assertEquals(1, page.get("page"));
        assertEquals(100, page.get("count"));
        verify(noticeMapper, never()).pageByUser(any(), any(), anyInt(), anyInt());
    }

    private SysUserEntity user(Long id) {
        SysUserEntity u = new SysUserEntity();
        u.setId(id);
        return u;
    }

    private void stubInsertAssignsId(long id) {
        when(noticeMapper.insert(any(SysNoticeEntity.class))).thenAnswer(inv -> {
            SysNoticeEntity n = inv.getArgument(0);
            n.setId(id);
            return 1;
        });
    }
}
