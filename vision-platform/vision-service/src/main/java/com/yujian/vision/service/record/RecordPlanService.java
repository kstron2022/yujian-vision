package com.yujian.vision.service.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.record.entity.GbRecordPlanEntity;
import com.yujian.vision.service.record.mapper.GbRecordPlanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 录像计划（docs/09 M2 #3）：week_plan 为 7x48 半小时布尔矩阵 JSON
 * （0=周一..6=周日, slot = hour*2 + minute/30）。
 * <p>通道计划（plan_type=2, 按 channel_db_id 唯一）优先于全局默认（plan_type=1, 全表唯一一行）;
 * 无计划行或 enable=0 不录。命中后由 CloudRecordService 在流就绪时调 ZLM startRecord(type=1)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordPlanService {

    /** 矩阵行数（7 天, 0=周一..6=周日） */
    public static final int DAYS = 7;
    /** 矩阵列数（48 个半小时槽） */
    public static final int SLOTS = 48;

    private final GbRecordPlanMapper planMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final ObjectMapper json;

    /** 计划回显行（通道行带通道名称回显） */
    public record PlanRow(Long id, Integer planType, Long channelDbId, String channelName,
            Integer enable, boolean[][] weekPlan) {
    }

    /** 计划保存入参（planType=1 时 channelDbId 须为空; planType=2 时必填） */
    public record PlanUpsert(Integer planType, Long channelDbId, Integer enable, boolean[][] weekPlan) {
    }

    // ---------------------------------------------------------------- 查询

    /** 全量列表: 全局默认行 + 通道行（通道行带通道名称回显; 通道已删除时名称置空不炸） */
    public List<PlanRow> list() {
        List<GbRecordPlanEntity> rows = planMapper.selectList(
                new LambdaQueryWrapper<GbRecordPlanEntity>()
                        .orderByAsc(GbRecordPlanEntity::getPlanType)
                        .orderByAsc(GbRecordPlanEntity::getChannelDbId));
        // 通道名称回显: 一次批量取, 不逐行查库
        Map<Long, String> names = new HashMap<>();
        List<Long> channelIds = rows.stream()
                .filter(r -> r.getChannelDbId() != null)
                .map(GbRecordPlanEntity::getChannelDbId)
                .distinct()
                .toList();
        if (!channelIds.isEmpty()) {
            for (GbDeviceChannelEntity c : channelMapper.selectList(
                    new LambdaQueryWrapper<GbDeviceChannelEntity>()
                            .in(GbDeviceChannelEntity::getId, channelIds))) {
                names.put(c.getId(), channelName(c));
            }
        }
        List<PlanRow> out = new ArrayList<>(rows.size());
        for (GbRecordPlanEntity row : rows) {
            out.add(new PlanRow(row.getId(), row.getPlanType(), row.getChannelDbId(),
                    names.get(row.getChannelDbId()), row.getEnable(), parseOrNull(row.getWeekPlan())));
        }
        return out;
    }

    // ---------------------------------------------------------------- 保存

    /**
     * 保存计划（upsert）: plan_type=1 唯一行 upsert; plan_type=2 按 channel_db_id upsert。
     * weekPlan 结构校验（7x48 布尔）不合法抛 400。
     * B3 P1: 加 @Transactional —— upsertChannel 是"查已有行→插入/更新"两步, 无事务时并发
     * 双提交可插出两行（计划判定 selectOne LIMIT 1 虽不炸但语义混乱）。
     */
    @Transactional
    public Long upsert(PlanUpsert req) {
        if (req == null || req.planType() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "planType 不能为空");
        }
        boolean[][] matrix = req.weekPlan();
        requireMatrix(matrix);
        int enable = req.enable() != null && req.enable() == 1 ? 1 : 0;
        if (req.planType() == GbRecordPlanEntity.TYPE_GLOBAL) {
            if (req.channelDbId() != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "全局默认计划不携带 channelDbId");
            }
            return upsertGlobal(enable, matrix);
        }
        if (req.planType() == GbRecordPlanEntity.TYPE_CHANNEL) {
            if (req.channelDbId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "通道计划必须携带 channelDbId");
            }
            return upsertChannel(req.channelDbId(), enable, matrix);
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "planType 仅支持 1(全局默认)/2(通道)");
    }

    /** 全局默认计划: 全表唯一一行, 有则改无则插 */
    private Long upsertGlobal(int enable, boolean[][] matrix) {
        GbRecordPlanEntity exists = planMapper.selectOne(new LambdaQueryWrapper<GbRecordPlanEntity>()
                .eq(GbRecordPlanEntity::getPlanType, GbRecordPlanEntity.TYPE_GLOBAL)
                .isNull(GbRecordPlanEntity::getChannelDbId)
                .last("LIMIT 1"));
        GbRecordPlanEntity row = new GbRecordPlanEntity();
        row.setName("全局默认计划");
        row.setPlanType(GbRecordPlanEntity.TYPE_GLOBAL);
        row.setEnable(enable);
        row.setWeekPlan(toJson(matrix));
        if (exists == null) {
            planMapper.insert(row);
            log.info("新建全局默认录像计划: id={}", row.getId());
            return row.getId();
        }
        row.setId(exists.getId());
        planMapper.updateById(row);
        log.info("更新全局默认录像计划: id={}", exists.getId());
        return exists.getId();
    }

    /** 通道计划: 按 channel_db_id 唯一, 有则改无则插; 通道不存在/已删除拒绝 */
    private Long upsertChannel(Long channelDbId, int enable, boolean[][] matrix) {
        GbDeviceChannelEntity ch = channelMapper.selectOne(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getId, channelDbId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .last("LIMIT 1"));
        if (ch == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "通道不存在: " + channelDbId);
        }
        GbRecordPlanEntity exists = planMapper.selectOne(new LambdaQueryWrapper<GbRecordPlanEntity>()
                .eq(GbRecordPlanEntity::getPlanType, GbRecordPlanEntity.TYPE_CHANNEL)
                .eq(GbRecordPlanEntity::getChannelDbId, channelDbId)
                .last("LIMIT 1"));
        GbRecordPlanEntity row = new GbRecordPlanEntity();
        row.setName("通道计划-" + channelName(ch));
        row.setPlanType(GbRecordPlanEntity.TYPE_CHANNEL);
        row.setChannelDbId(channelDbId);
        row.setEnable(enable);
        row.setWeekPlan(toJson(matrix));
        if (exists == null) {
            planMapper.insert(row);
            log.info("新建通道录像计划: id={} channel={}", row.getId(), ch.getChannelId());
            return row.getId();
        }
        row.setId(exists.getId());
        planMapper.updateById(row);
        log.info("更新通道录像计划: id={} channel={}", exists.getId(), ch.getChannelId());
        return exists.getId();
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 该通道当前时刻是否应录: 通道计划优先, 否则全局默认; 无计划行/enable=0 不录。
     */
    public boolean isRecordingDue(Long channelDbId, LocalDateTime now) {
        if (now == null) {
            return false;
        }
        GbRecordPlanEntity plan = channelDbId == null ? null : selectChannelPlan(channelDbId);
        if (plan == null) {
            plan = selectGlobalPlan();
        }
        if (plan == null || plan.getEnable() == null || plan.getEnable() != 1) {
            return false;
        }
        boolean[][] matrix = parseOrNull(plan.getWeekPlan());
        if (matrix == null) {
            return false;
        }
        return matrix[dayIndex(now)][slotIndex(now)];
    }

    /** 0=周一..6=周日（与前端矩阵行序一致） */
    static int dayIndex(LocalDateTime t) {
        return t.getDayOfWeek().getValue() - 1;
    }

    /** slot = hour*2 + minute/30（半小时粒度） */
    static int slotIndex(LocalDateTime t) {
        return t.getHour() * 2 + t.getMinute() / 30;
    }

    private GbRecordPlanEntity selectChannelPlan(Long channelDbId) {
        return planMapper.selectOne(new LambdaQueryWrapper<GbRecordPlanEntity>()
                .eq(GbRecordPlanEntity::getPlanType, GbRecordPlanEntity.TYPE_CHANNEL)
                .eq(GbRecordPlanEntity::getChannelDbId, channelDbId)
                .last("LIMIT 1"));
    }

    private GbRecordPlanEntity selectGlobalPlan() {
        return planMapper.selectOne(new LambdaQueryWrapper<GbRecordPlanEntity>()
                .eq(GbRecordPlanEntity::getPlanType, GbRecordPlanEntity.TYPE_GLOBAL)
                .isNull(GbRecordPlanEntity::getChannelDbId)
                .last("LIMIT 1"));
    }

    // ---------------------------------------------------------------- week_plan JSON

    /**
     * 解析 7x48 矩阵: 结构不合法返回 null（不炸运行时链路, 只按"不应录"处理）;
     * upsert 入参校验用 {@link #requireMatrix}。
     */
    boolean[][] parseOrNull(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = json.readTree(raw);
            if (!root.isArray() || root.size() != DAYS) {
                return null;
            }
            boolean[][] out = new boolean[DAYS][SLOTS];
            for (int d = 0; d < DAYS; d++) {
                JsonNode day = root.get(d);
                if (!day.isArray() || day.size() != SLOTS) {
                    return null;
                }
                for (int s = 0; s < SLOTS; s++) {
                    JsonNode cell = day.get(s);
                    if (cell == null || !cell.isBoolean()) {
                        return null;
                    }
                    out[d][s] = cell.asBoolean();
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("week_plan 结构非法, 按\"不应录\"处理: {}", e.getMessage());
            return null;
        }
    }

    /** upsert 路径的严格校验: 不合法直接 400（与 parseOrNull 同一套结构规则） */
    static void requireMatrix(boolean[][] matrix) {
        if (matrix == null || matrix.length != DAYS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "weekPlan 须为 7x48 布尔矩阵");
        }
        for (boolean[] day : matrix) {
            if (day == null || day.length != SLOTS) {
                throw new BizException(ErrorCode.BAD_REQUEST, "weekPlan 须为 7x48 布尔矩阵");
            }
        }
    }

    private String toJson(boolean[][] matrix) {
        try {
            return json.writeValueAsString(matrix);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "weekPlan 序列化失败");
        }
    }

    /** 通道显示名: custom_name 优先, 否则 name（B3 通道自定义名称口径） */
    static String channelName(GbDeviceChannelEntity c) {
        return StringUtils.hasText(c.getCustomName()) ? c.getCustomName() : c.getName();
    }
}
