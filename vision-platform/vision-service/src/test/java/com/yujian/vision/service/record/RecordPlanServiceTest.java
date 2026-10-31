package com.yujian.vision.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.record.entity.GbRecordPlanEntity;
import com.yujian.vision.service.record.mapper.GbRecordPlanMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 录像计划单元测试: isRecordingDue 的通道覆盖/全局默认/无计划/时段边界 + week_plan 结构校验。
 * 纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class RecordPlanServiceTest {

    @Mock
    private GbRecordPlanMapper planMapper;

    @Mock
    private GbDeviceChannelMapper channelMapper;

    private RecordPlanService service;

    @BeforeEach
    void setUp() {
        service = new RecordPlanService(planMapper, channelMapper, new ObjectMapper());
    }

    /** 全 false 矩阵, 仅 (day, slot) 置位 */
    private static boolean[][] matrix(int day, int slot) {
        boolean[][] m = new boolean[RecordPlanService.DAYS][RecordPlanService.SLOTS];
        m[day][slot] = true;
        return m;
    }

    private static GbRecordPlanEntity plan(int type, Long channelDbId, int enable, boolean[][] m)
            throws Exception {
        GbRecordPlanEntity e = new GbRecordPlanEntity();
        e.setName("p");
        e.setPlanType(type);
        e.setChannelDbId(channelDbId);
        e.setEnable(enable);
        e.setWeekPlan(new ObjectMapper().writeValueAsString(m));
        return e;
    }

    @Test
    @DisplayName("通道计划命中时点 → 应录")
    void channelPlanDue() throws Exception {
        // 周三(2) 09:00 → slot 18
        when(planMapper.selectOne(any()))
                .thenReturn(plan(GbRecordPlanEntity.TYPE_CHANNEL, 9L, 1, matrix(2, 18)));

        assertTrue(service.isRecordingDue(9L, LocalDateTime.of(2026, 9, 9, 9, 0)));
        assertTrue(service.isRecordingDue(9L, LocalDateTime.of(2026, 9, 9, 9, 29)));
    }

    @Test
    @DisplayName("通道计划槽未置位 → 不录(不回落全局的同时槽)")
    void channelPlanUnsetSlotDoesNotFallBack() throws Exception {
        GbRecordPlanEntity ch = plan(GbRecordPlanEntity.TYPE_CHANNEL, 9L, 1, matrix(2, 19));
        GbRecordPlanEntity global = plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(2, 18));
        when(planMapper.selectOne(any())).thenReturn(ch, global);

        assertFalse(service.isRecordingDue(9L, LocalDateTime.of(2026, 9, 9, 9, 0)));
    }

    @Test
    @DisplayName("无通道计划 → 回落全局默认")
    void fallsBackToGlobal() throws Exception {
        GbRecordPlanEntity global = plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(6, 47));
        when(planMapper.selectOne(any())).thenReturn(null, global);

        // 周日(6) 23:30 → slot 47
        assertTrue(service.isRecordingDue(5L, LocalDateTime.of(2026, 9, 13, 23, 30)));
    }

    @Test
    @DisplayName("无任何计划行 → 不录")
    void noPlanAtAll() {
        when(planMapper.selectOne(any())).thenReturn(null, (GbRecordPlanEntity) null);
        assertFalse(service.isRecordingDue(5L, LocalDateTime.of(2026, 9, 9, 9, 0)));
    }

    @Test
    @DisplayName("计划 enable=0 → 不录")
    void disabledPlanNeverRecords() throws Exception {
        when(planMapper.selectOne(any()))
                .thenReturn(plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 0, matrix(2, 18)));

        assertFalse(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 9, 9, 0)));
    }

    @Test
    @DisplayName("时段边界: 08:59 落 17 槽, 09:00 落 18 槽(半小时取整)")
    void slotBoundaries() throws Exception {
        when(planMapper.selectOne(any()))
                .thenReturn(plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(0, 17)));

        // 2026-09-07 是周一
        assertTrue(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 7, 8, 59)));
        assertFalse(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 7, 9, 0)));
    }

    @Test
    @DisplayName("星期映射: 0=周一..6=周日")
    void dayMappingMondayFirst() throws Exception {
        when(planMapper.selectOne(any()))
                .thenReturn(plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(0, 0)));
        assertTrue(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 7, 0, 0)));   // 周一
        assertFalse(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 13, 0, 0))); // 周日

        when(planMapper.selectOne(any()))
                .thenReturn(plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(6, 0)));
        assertTrue(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 13, 0, 5)));  // 周日
    }

    @Test
    @DisplayName("week_plan 结构非法 → 按不应录处理, 不炸")
    void malformedWeekPlanIsTreatedAsNotDue() {
        GbRecordPlanEntity bad = new GbRecordPlanEntity();
        bad.setPlanType(GbRecordPlanEntity.TYPE_GLOBAL);
        bad.setEnable(1);
        bad.setWeekPlan("{\"oops\":1}");
        when(planMapper.selectOne(any())).thenReturn(bad);

        assertFalse(service.isRecordingDue(null, LocalDateTime.of(2026, 9, 9, 9, 0)));
        assertNull(service.parseOrNull("not-json"));
        assertNull(service.parseOrNull(" "));
    }

    @Test
    @DisplayName("upsert: 矩阵/类型/通道入参不合法 → 400")
    void upsertRejectsBadInput() {
        assertThrows(BizException.class, () -> service.upsert(
                new RecordPlanService.PlanUpsert(GbRecordPlanEntity.TYPE_GLOBAL, null, 1,
                        new boolean[6][48])));
        assertThrows(BizException.class, () -> service.upsert(
                new RecordPlanService.PlanUpsert(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, null)));
        assertThrows(BizException.class, () -> service.upsert(
                new RecordPlanService.PlanUpsert(GbRecordPlanEntity.TYPE_CHANNEL, null, 1, matrix(0, 0))));
        assertThrows(BizException.class, () -> service.upsert(
                new RecordPlanService.PlanUpsert(GbRecordPlanEntity.TYPE_GLOBAL, 7L, 1, matrix(0, 0))));
        assertThrows(BizException.class, () -> service.upsert(
                new RecordPlanService.PlanUpsert(3, null, 1, matrix(0, 0))));
    }

    @Test
    @DisplayName("upsert: 全局默认无既有行 → 插入新行")
    void upsertGlobalInsertsWhenAbsent() throws Exception {
        when(planMapper.selectOne(any())).thenReturn(null);
        // 模拟 MyBatis-Plus 自增主键回填
        when(planMapper.insert(any(GbRecordPlanEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, GbRecordPlanEntity.class).setId(88L);
            return 1;
        });

        Long id = service.upsert(new RecordPlanService.PlanUpsert(
                GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(0, 0)));

        assertEquals(88L, id);
    }

    @Test
    @DisplayName("list(): 通道行回显通道名称(通道已删时为 null 不炸)")
    void listResolvesChannelNames() throws Exception {
        GbRecordPlanEntity global = plan(GbRecordPlanEntity.TYPE_GLOBAL, null, 1, matrix(0, 0));
        GbRecordPlanEntity ch = plan(GbRecordPlanEntity.TYPE_CHANNEL, 33L, 1, matrix(1, 2));
        when(planMapper.selectList(any())).thenReturn(List.of(global, ch));
        when(channelMapper.selectList(any())).thenReturn(List.of());

        List<RecordPlanService.PlanRow> rows = service.list();
        assertEquals(2, rows.size());
        assertNull(rows.get(0).channelName());  // 全局默认行无通道
        assertNull(rows.get(1).channelName());  // 通道已删除 → 名称置空不炸
        assertEquals(33L, rows.get(1).channelDbId());
    }
}
