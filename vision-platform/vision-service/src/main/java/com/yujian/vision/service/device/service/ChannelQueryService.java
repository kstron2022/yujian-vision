package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.media.service.SnapshotService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 通道查询（docs/03 §3, M1-9 A4）：分页列表/按父子关系组树/通道信息 patch（名称/云台开关）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelQueryService {

    private final GbDeviceChannelMapper channelMapper;
    /** 最新截图批量填充（M3-B7 阶段2, 仅列表页） */
    private final SnapshotService snapshots;

    /** 兼容旧签名（不限数据权限） */
    public IPage<GbDeviceChannelEntity> page(Long deviceId, String query, Integer status,
            Integer ptz, int page, int count) {
        return page(deviceId, query, status, ptz, page, count, null);
    }

    /**
     * 数据权限感知重载（B3 P0-2）: filter 为 null 或 all=true 不加条件; ids 非空按 IN 收口;
     * ids 为空 = 全禁 → 提前短路返回空页（.in 空集合非法, 不能下 SQL）。
     */
    public IPage<GbDeviceChannelEntity> page(Long deviceId, String query, Integer status,
            Integer ptz, int page, int count, ChannelDataScopeService.ScopeFilter filter) {
        LambdaQueryWrapper<GbDeviceChannelEntity> qw = new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .eq(deviceId != null, GbDeviceChannelEntity::getDeviceDbId, deviceId)
                .and(StringUtils.hasText(query), w -> w
                        .like(GbDeviceChannelEntity::getChannelId, query)
                        .or().like(GbDeviceChannelEntity::getName, query)
                        .or().like(GbDeviceChannelEntity::getCustomName, query))
                .eq(status != null, GbDeviceChannelEntity::getStatus, status)
                .gt(ptz != null && ptz == 1, GbDeviceChannelEntity::getPtzType, 0)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .orderByAsc(GbDeviceChannelEntity::getId);
        if (filter != null && !filter.all()) {
            if (filter.ids().isEmpty()) {
                return new Page<>(page, count, 0);
            }
            qw.in(GbDeviceChannelEntity::getId, filter.ids());
        }
        IPage<GbDeviceChannelEntity> p = channelMapper.selectPage(new Page<>(page, count), qw);
        if (!p.getRecords().isEmpty()) {
            // 目录节点标记（M5-B2 联调）: 一次 IN 查出涉入设备的全部父编码集合,
            // 命中即目录; parental=1 兜底暂无子节点的空目录。不信对端上报的 parental
            // ——LiveGBS 对目录节点报 parental=0 却挂 children（BUG-2 同根源）
            markDirectories(p.getRecords());
            // M3-B7 阶段2: 列表页批量填充最新截图（一次 IN 查, 无图=null 前端占位图标）。
            // 吞异常——列表可用性优先, 不因截图填充失败（含差异化部署 snapshot.enabled=false）挂列表;
            // tree() 不填充: 直播侧栏不需要, 且全量填充开销大（挂账备注）
            try {
                snapshots.fillSnapshots(p.getRecords());
            } catch (Exception e) {
                log.warn("通道列表截图填充失败(忽略, 列表可用性优先): {}", e.getMessage());
            }
        }
        return p;
    }

    /**
     * 批量标记目录节点: channelId 出现在本设备任一存活通道的 parent_id 中 → 目录,
     * 再 OR parental=1 兜底空目录。只查 parent_id 一列（结构事实, 与上报字段无关）。
     */
    private void markDirectories(List<GbDeviceChannelEntity> records) {
        java.util.Set<Long> deviceIds = new java.util.HashSet<>();
        for (GbDeviceChannelEntity c : records) {
            deviceIds.add(c.getDeviceDbId());
        }
        List<GbDeviceChannelEntity> refs = channelMapper.selectList(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .select(GbDeviceChannelEntity::getParentId)
                        .in(GbDeviceChannelEntity::getDeviceDbId, deviceIds)
                        .isNotNull(GbDeviceChannelEntity::getParentId)
                        .ne(GbDeviceChannelEntity::getParentId, "")
                        .isNull(GbDeviceChannelEntity::getDeletedTime));
        java.util.Set<String> parentIds = new java.util.HashSet<>();
        for (GbDeviceChannelEntity r : refs) {
            parentIds.add(r.getParentId());
        }
        for (GbDeviceChannelEntity c : records) {
            c.setDirectory(parentIds.contains(c.getChannelId())
                    || (c.getParental() != null && c.getParental() == 1));
        }
    }

    /** 通道树（按 parentId 组织, 虚拟分组节点 parental=1 也入树; 不限数据权限） */
    public List<TreeNode> tree(Long deviceId) {
        return tree(deviceId, null);
    }

    /**
     * 数据权限感知重载（B3 P0-2）: 加载通道 list 后按 filter 过滤再组树;
     * 父节点不在许可集时其子节点提升为根节点（树结构仍可导航）。
     */
    public List<TreeNode> tree(Long deviceId, ChannelDataScopeService.ScopeFilter filter) {
        List<GbDeviceChannelEntity> all = channelMapper.selectList(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(deviceId != null, GbDeviceChannelEntity::getDeviceDbId, deviceId)
                        .isNull(GbDeviceChannelEntity::getDeletedTime)
                        .orderByAsc(GbDeviceChannelEntity::getId));
        if (filter != null && !filter.all()) {
            List<GbDeviceChannelEntity> kept = new ArrayList<>(all.size());
            for (GbDeviceChannelEntity c : all) {
                if (filter.ids().contains(c.getId())) {
                    kept.add(c);
                }
            }
            all = kept;
        }
        Map<Long, TreeNode> nodes = new HashMap<>();
        for (GbDeviceChannelEntity c : all) {
            nodes.put(c.getId(), new TreeNode(c, new ArrayList<>()));
        }
        List<TreeNode> roots = new ArrayList<>();
        for (GbDeviceChannelEntity c : all) {
            TreeNode node = nodes.get(c.getId());
            // parentId 是国标编码, 先映射到库内节点再挂树
            GbDeviceChannelEntity p = c.getParentId() == null ? null : findByChannelId(all, c.getParentId());
            TreeNode parent = p == null ? null : nodes.get(p.getId());
            if (parent != null && parent != node) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    private GbDeviceChannelEntity findByChannelId(List<GbDeviceChannelEntity> all, String channelId) {
        for (GbDeviceChannelEntity c : all) {
            if (c.getChannelId() != null && c.getChannelId().equals(channelId)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 修改通道（patch 语义, docs/09 M1-8 挂账"强制云台"开关）:
     * customName 传 null 不改; ptzEnabled 传 null 恢复自动（列置 NULL）, 0/1 显式设置。
     * ptzEnabled 需列置 NULL, MyBatis-Plus updateById 对 null 字段跳过更新, 故走 UpdateWrapper 显式 set。
     */
    public void updateChannel(Long channelDbId, String customName, Integer ptzEnabled, Integer ptzType) {
        GbDeviceChannelEntity c = channelMapper.selectOne(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .eq(GbDeviceChannelEntity::getId, channelDbId)
                        .isNull(GbDeviceChannelEntity::getDeletedTime));
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通道不存在: " + channelDbId);
        }
        if (ptzEnabled != null && ptzEnabled != 0 && ptzEnabled != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ptzEnabled 仅支持 0/1（null=恢复自动）: " + ptzEnabled);
        }
        // ptzType=null 表示不改（与 ptzEnabled 的 null=清空恢复自动语义不同——云台类型没有"自动恢复"概念,
        // 设备侧真实上报值由目录收敛写入; 人工值不会被缺省上报冲掉, 见 CatalogSyncService.applyItemFields）
        if (ptzType != null && (ptzType < 0 || ptzType > 5)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ptzType 仅支持 0~5（0=未知, 1球机 2半球 3固定枪机 4遥控枪机 5不支持）: " + ptzType);
        }
        LambdaUpdateWrapper<GbDeviceChannelEntity> patch = new LambdaUpdateWrapper<GbDeviceChannelEntity>()
                .eq(GbDeviceChannelEntity::getId, channelDbId)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                // ptzEnabled=null → SET ptz_enabled=NULL（恢复按 ptzType 自动）
                .set(GbDeviceChannelEntity::getPtzEnabled, ptzEnabled);
        if (customName != null) {
            patch.set(GbDeviceChannelEntity::getCustomName, customName);
        }
        if (ptzType != null) {
            patch.set(GbDeviceChannelEntity::getPtzType, ptzType);
        }
        channelMapper.update(null, patch);
    }

    public record TreeNode(GbDeviceChannelEntity channel, List<TreeNode> children) {
    }
}
