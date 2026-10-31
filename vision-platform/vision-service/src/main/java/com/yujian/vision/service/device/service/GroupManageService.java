package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.entity.GbGroupChannelRefEntity;
import com.yujian.vision.service.device.entity.GbGroupEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.mapper.GbGroupChannelRefMapper;
import com.yujian.vision.service.device.mapper.GbGroupMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 自定义分组管理（docs/09 M2 #5, 决策 D13: gb_group 为前端主树, Catalog 为原始视图）。
 * 树操作在内存完成（分组量为小几百级）, ancestors 仅落库冗余。
 * <p>树写互斥（M3 收口）: 写方法经 {@link #withTreeLock} 串行——242 常驻双实例共用 Redis/DB,
 * 进程内手段挡不住跨实例并发写（两实例同时挪挂载点会互相覆盖 ancestors）, Redisson 锁即跨实例互斥。</p>
 */
@Service
@RequiredArgsConstructor
public class GroupManageService {

    /** 全树一把锁: 树操作低频, 挪挂载点会读全表重写后代, 分组级锁粒度得不偿失 */
    private static final String LOCK_KEY = "gb:lock:group-tree";
    /** 等锁时长: 吸收"双管理员同时提交"的正常并发, 超过即视为忙 */
    private static final long LOCK_WAIT_SECONDS = 5;

    private final GbGroupMapper groupMapper;
    private final GbGroupChannelRefMapper refMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbDeviceMapper deviceMapper;
    private final RedissonClient redisson;
    private final TransactionTemplate txTemplate;

    // ---------------------------------------------------------------- 树查询

    /** 分组树（不含通道, 管理页/角色授权树共用） */
    public List<GroupNode> tree() {
        return buildTree(listValid(), null);
    }

    /** 分组树 + 通道叶子（实时预览"分组视图"用; channelFilter 传数据权限过滤, null=不过滤） */
    public List<GroupChannelNode> treeWithChannels(Predicate<GbDeviceChannelEntity> channelFilter) {
        List<GbGroupEntity> groups = listValid();
        Map<Long, List<GbDeviceChannelEntity>> byGroup = new HashMap<>();
        if (!groups.isEmpty()) {
            List<GbGroupChannelRefEntity> refs = refMapper.selectList(
                    new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                            .in(GbGroupChannelRefEntity::getGroupId, groupIds(groups)));
            Set<Long> chIds = new HashSet<>();
            Map<Long, Long> chGroup = new HashMap<>();
            for (GbGroupChannelRefEntity r : refs) {
                chIds.add(r.getChannelDbId());
                chGroup.put(r.getChannelDbId(), r.getGroupId());
            }
            if (!chIds.isEmpty()) {
                for (GbDeviceChannelEntity c : channelMapper.selectList(
                        new LambdaQueryWrapper<GbDeviceChannelEntity>()
                                .in(GbDeviceChannelEntity::getId, chIds)
                                .isNull(GbDeviceChannelEntity::getDeletedTime))) {
                    if (channelFilter == null || channelFilter.test(c)) {
                        byGroup.computeIfAbsent(chGroup.get(c.getId()), k -> new ArrayList<>()).add(c);
                    }
                }
            }
        }
        Map<Long, GroupChannelNode> nodes = new LinkedHashMap<>();
        for (GbGroupEntity g : groups) {
            nodes.put(g.getId(), new GroupChannelNode(g, new ArrayList<>(), new ArrayList<>()));
        }
        List<GroupChannelNode> roots = new ArrayList<>();
        for (GbGroupEntity g : groups) {
            GroupChannelNode node = nodes.get(g.getId());
            GroupChannelNode parent = nodes.get(g.getParentId());
            if (parent != null && parent != node) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        for (Map.Entry<Long, List<GbDeviceChannelEntity>> e : byGroup.entrySet()) {
            GroupChannelNode node = nodes.get(e.getKey());
            if (node != null) {
                node.channels().addAll(e.getValue());
            }
        }
        // 未挂任何分组的通道不进分组视图（预览页可切"目录视图"查看全部）;
        // 数据权限过滤后整棵子树无可见通道的分组裁掉（受限用户空态由前端区分"无分组/未授权"）
        if (channelFilter != null) {
            roots.removeIf(node -> !hasVisibleChannel(node));
        }
        return roots;
    }

    private boolean hasVisibleChannel(GroupChannelNode node) {
        if (!node.channels().isEmpty()) {
            return true;
        }
        for (GroupChannelNode child : node.children()) {
            if (hasVisibleChannel(child)) {
                return true;
            }
        }
        return false;
    }

    /** 分组内通道列表（不过滤） */
    public List<GbDeviceChannelEntity> channels(Long groupId) {
        return channels(groupId, null);
    }

    /**
     * 分组内通道列表（B3 P0-2 纵深防御: filter 传数据权限谓词, null=不过滤）。
     */
    public List<GbDeviceChannelEntity> channels(Long groupId, Predicate<GbDeviceChannelEntity> filter) {
        requireGroup(groupId);
        List<GbGroupChannelRefEntity> refs = refMapper.selectList(
                new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                        .eq(GbGroupChannelRefEntity::getGroupId, groupId));
        if (refs.isEmpty()) {
            return List.of();
        }
        Set<Long> chIds = new HashSet<>();
        for (GbGroupChannelRefEntity r : refs) {
            chIds.add(r.getChannelDbId());
        }
        List<GbDeviceChannelEntity> rows = channelMapper.selectList(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                .in(GbDeviceChannelEntity::getId, chIds)
                .isNull(GbDeviceChannelEntity::getDeletedTime)
                .orderByAsc(GbDeviceChannelEntity::getId));
        if (filter == null) {
            return rows;
        }
        List<GbDeviceChannelEntity> out = new ArrayList<>(rows.size());
        for (GbDeviceChannelEntity c : rows) {
            if (filter.test(c)) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- CRUD

    public Long create(Long parentId, String groupName, Integer sortOrder) {
        return withTreeLock(() -> txTemplate.execute(status -> {
            GbGroupEntity parent = null;
            if (parentId != null && parentId != 0) {
                parent = requireGroup(parentId);
            }
            GbGroupEntity g = new GbGroupEntity();
            g.setParentId(parent == null ? 0L : parent.getId());
            g.setAncestors(parent == null ? "0" : parent.getAncestors() + "," + parent.getId());
            g.setGroupName(requireName(groupName));
            g.setSortOrder(sortOrder == null ? 0 : sortOrder);
            groupMapper.insert(g);
            return g.getId();
        }));
    }

    public void update(Long id, Long parentId, String groupName, Integer sortOrder) {
        withTreeLock(() -> txTemplate.executeWithoutResult(status -> updateInTx(id, parentId, groupName, sortOrder)));
    }

    /** 包私有便于单测: 锁与事务之外的纯业务体（挪挂载点校验+后代 ancestors 重写） */
    void updateInTx(Long id, Long parentId, String groupName, Integer sortOrder) {
        GbGroupEntity self = requireGroup(id);
        if (StringUtils.hasText(groupName)) {
            self.setGroupName(groupName.trim());
        }
        if (sortOrder != null) {
            self.setSortOrder(sortOrder);
        }
        // 支持挪挂载点: 校验合法后重算自身与全部后代的 ancestors
        if (parentId != null && parentId != 0 && !parentId.equals(self.getParentId())) {
            if (parentId.equals(self.getId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "父分组不能是自身");
            }
            GbGroupEntity parent = requireGroup(parentId);
            if (isDescendant(parent, self)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "父分组不能是自身的子节点");
            }
            String oldPrefix = self.getAncestors() + "," + self.getId();
            String newPrefix = parent.getAncestors() + "," + parent.getId() + "," + self.getId();
            self.setParentId(parent.getId());
            self.setAncestors(parent.getAncestors() + "," + parent.getId());
            List<GbGroupEntity> all = listValid();
            for (GbGroupEntity d : all) {
                if (isUnderPrefix(d.getAncestors(), oldPrefix)) {
                    GbGroupEntity patch = new GbGroupEntity();
                    patch.setId(d.getId());
                    patch.setAncestors(newPrefix + d.getAncestors().substring(oldPrefix.length()));
                    groupMapper.updateById(patch);
                }
            }
        } else if (parentId != null && parentId == 0 && self.getParentId() != 0) {
            // 挪到根
            String oldPrefix = self.getAncestors() + "," + self.getId();
            self.setParentId(0L);
            self.setAncestors("0");
            List<GbGroupEntity> all = listValid();
            for (GbGroupEntity d : all) {
                if (isUnderPrefix(d.getAncestors(), oldPrefix)) {
                    GbGroupEntity patch = new GbGroupEntity();
                    patch.setId(d.getId());
                    patch.setAncestors("0," + self.getId() + d.getAncestors().substring(oldPrefix.length()));
                    groupMapper.updateById(patch);
                }
            }
        }
        groupMapper.updateById(self);
    }

    public void delete(Long id) {
        withTreeLock(() -> txTemplate.executeWithoutResult(status -> deleteInTx(id)));
    }

    /** 包私有便于单测: 锁与事务之外的纯业务体（删前校验+软删置位） */
    void deleteInTx(Long id) {
        requireGroup(id);
        Integer childCount = groupMapper.selectCount(new LambdaQueryWrapper<GbGroupEntity>()
                .eq(GbGroupEntity::getParentId, id)
                .isNull(GbGroupEntity::getDeletedTime)).intValue();
        if (childCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "存在子分组，请先删除子分组");
        }
        Integer refCount = refMapper.selectCount(new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                .eq(GbGroupChannelRefEntity::getGroupId, id)).intValue();
        if (refCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分组下仍有 " + refCount + " 个通道，请先解绑");
        }
        GbGroupEntity patch = new GbGroupEntity();
        patch.setId(id);
        patch.setDeletedTime(java.time.LocalDateTime.now());
        groupMapper.updateById(patch);
    }

    // ---------------------------------------------------------------- 通道绑定

    /**
     * 全量覆盖绑定（前端勾选树一次提交）; channelDbIds 空数组=清空。
     * 单分组约束（docs/02 §2.12 / docs/03 §3.1 修订）: 通道仅可属一个分组,
     * 目标列表中已被其他分组绑定的通道拒绝并列明所在分组。
     */
    public void bindChannels(Long groupId, List<Long> channelDbIds) {
        withTreeLock(() -> txTemplate.executeWithoutResult(status -> bindChannelsInTx(groupId, channelDbIds)));
    }

    /** 包私有便于单测: 锁与事务之外的纯业务体（单分组约束校验+全量覆盖绑定） */
    void bindChannelsInTx(Long groupId, List<Long> channelDbIds) {
        requireGroup(groupId);
        Set<Long> ids = new HashSet<>();
        if (channelDbIds != null) {
            ids.addAll(channelDbIds);
        }
        if (!ids.isEmpty()) {
            Integer valid = channelMapper.selectCount(new LambdaQueryWrapper<GbDeviceChannelEntity>()
                    .in(GbDeviceChannelEntity::getId, ids)
                    .isNull(GbDeviceChannelEntity::getDeletedTime)).intValue();
            if (valid != ids.size()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "包含无效或已删除的通道");
            }
            // 单分组约束: ref 里 groupId != 本组的即冲突（本组已有绑定合法, 全量覆盖会重写）
            List<GbGroupChannelRefEntity> conflicts = refMapper.selectList(
                    new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                            .in(GbGroupChannelRefEntity::getChannelDbId, ids)
                            .ne(GbGroupChannelRefEntity::getGroupId, groupId));
            if (!conflicts.isEmpty()) {
                Map<Long, GbGroupEntity> nameById = new HashMap<>();
                for (GbGroupEntity g : listValid()) {
                    nameById.put(g.getId(), g);
                }
                Set<String> groupNames = new HashSet<>();
                for (GbGroupChannelRefEntity r : conflicts) {
                    GbGroupEntity n = nameById.get(r.getGroupId());
                    groupNames.add(n != null ? n.getGroupName() : ("分组#" + r.getGroupId()));
                }
                throw new BizException(ErrorCode.BAD_REQUEST,
                        conflicts.size() + " 个通道已绑定到其他分组（" + String.join("、", groupNames)
                                + "），请先在对应分组中解绑");
            }
        }
        refMapper.delete(new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                .eq(GbGroupChannelRefEntity::getGroupId, groupId));
        for (Long chId : ids) {
            GbGroupChannelRefEntity ref = new GbGroupChannelRefEntity();
            ref.setGroupId(groupId);
            ref.setChannelDbId(chId);
            refMapper.insert(ref);
        }
    }

    /**
     * 通道绑定视图（BindChannelsDrawer 数据源）: 全部可见通道（filter=数据权限, null=不过滤）
     * + 各通道当前所属分组。单分组约束下 boundGroups 至多一个; 前端据此隐藏"已绑其他分组"的通道。
     * 目录节点（下级平台行政区划/平台根等虚拟分组）只打 directory 标不剔除——前端绑定树按
     * parentId 组层级, 目录作级联勾选的分组壳, 保存仍只取通道叶子（判定与
     * ChannelQueryService.markDirectories 同口径, filter 前先算防数据权限滤掉父节点误判）。
     */
    public BindView bindView(Long groupId, Predicate<GbDeviceChannelEntity> filter) {
        requireGroup(groupId);
        List<GbDeviceChannelEntity> rows = channelMapper.selectList(
                new LambdaQueryWrapper<GbDeviceChannelEntity>()
                        .isNull(GbDeviceChannelEntity::getDeletedTime)
                        .orderByAsc(GbDeviceChannelEntity::getDeviceDbId)
                        .orderByAsc(GbDeviceChannelEntity::getId));
        java.util.Set<String> parentIds = new java.util.HashSet<>();
        for (GbDeviceChannelEntity c : rows) {
            if (c.getParentId() != null && !c.getParentId().isEmpty()) {
                parentIds.add(c.getParentId());
            }
        }
        for (GbDeviceChannelEntity c : rows) {
            c.setDirectory(parentIds.contains(c.getChannelId())
                    || (c.getParental() != null && c.getParental() == 1));
        }
        if (filter != null) {
            List<GbDeviceChannelEntity> visible = new ArrayList<>(rows.size());
            for (GbDeviceChannelEntity c : rows) {
                if (filter.test(c)) {
                    visible.add(c);
                }
            }
            rows = visible;
        }
        // channelDbId -> 所属分组(多绑存量数据兼容展示, 新绑定已被单分组校验拦截)
        Map<Long, List<Long>> chGroups = new HashMap<>();
        for (GbGroupChannelRefEntity r : refMapper.selectList(null)) {
            chGroups.computeIfAbsent(r.getChannelDbId(), k -> new ArrayList<>()).add(r.getGroupId());
        }
        Map<Long, String> groupNames = new HashMap<>();
        for (GbGroupEntity g : listValid()) {
            groupNames.put(g.getId(), g.getGroupName());
        }
        Map<Long, String> deviceNames = new HashMap<>();
        for (GbDeviceEntity d : deviceMapper.selectList(null)) {
            deviceNames.put(d.getId(), d.getName() != null && !d.getName().isEmpty()
                    ? d.getName() : d.getDeviceId());
        }
        List<BindViewChannel> channels = new ArrayList<>(rows.size());
        for (GbDeviceChannelEntity c : rows) {
            List<Long> gids = chGroups.getOrDefault(c.getId(), List.of());
            List<String> gnames = new ArrayList<>(gids.size());
            for (Long gid : gids) {
                String n = groupNames.get(gid);
                gnames.add(n != null ? n : ("分组#" + gid));
            }
            // 展示名与前端既有语义一致: customName > name > channelId
            String showName = c.getCustomName() != null && !c.getCustomName().isEmpty()
                    ? c.getCustomName()
                    : (c.getName() != null && !c.getName().isEmpty() ? c.getName() : c.getChannelId());
            channels.add(new BindViewChannel(c.getId(), c.getChannelId(), showName,
                    c.getStatus(), c.getPtzType(), c.getDeviceDbId(),
                    deviceNames.getOrDefault(c.getDeviceDbId(), ""), gids, gnames,
                    Boolean.TRUE.equals(c.getDirectory())));
        }
        return new BindView(groupId, channels);
    }

    // ---------------------------------------------------------------- 供数据权限用

    public List<GbGroupEntity> listValid() {
        return groupMapper.selectList(new LambdaQueryWrapper<GbGroupEntity>()
                .isNull(GbGroupEntity::getDeletedTime)
                .orderByAsc(GbGroupEntity::getSortOrder)
                .orderByAsc(GbGroupEntity::getId));
    }

    /** 给定分组集合展开为"自身+全部后代"的 id 集合（数据权限子树生效用） */
    public Set<Long> expandWithDescendants(Collection<Long> rootIds) {
        Set<Long> result = new HashSet<>(rootIds);
        Map<Long, GbGroupEntity> byId = new HashMap<>();
        for (GbGroupEntity g : listValid()) {
            byId.put(g.getId(), g);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (GbGroupEntity g : byId.values()) {
                if (result.contains(g.getId())) {
                    continue;
                }
                if (g.getParentId() != null && result.contains(g.getParentId())) {
                    result.add(g.getId());
                    changed = true;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 树写互斥

    /**
     * 树写互斥（M3 收口）: 242 常驻双实例共用 Redis/DB, 进程内手段挡不住跨实例并发写
     * （两实例同时挪挂载点会互相覆盖 ancestors）, Redisson 锁即跨实例互斥。
     * 锁必须包住整个事务——先拿锁再开事务、提交后才放锁: 若在事务内拿放锁,
     * 对端实例会在本事务提交前读到旧 ancestors 写库, 互覆盖窗口依旧在。
     * 树操作低频, 等锁 5s 吸收双管理员同时提交, 超时按忙拒绝。
     */
    private <T> T withTreeLock(Supplier<T> action) {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分组操作被中断，请重试");
        }
        if (!locked) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "分组操作正在进行中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void withTreeLock(Runnable action) {
        withTreeLock(() -> {
            action.run();
            return null;
        });
    }

    // ---------------------------------------------------------------- 内部

    private List<GroupNode> buildTree(List<GbGroupEntity> groups, Predicate<GbGroupEntity> filter) {
        Map<Long, GroupNode> nodes = new LinkedHashMap<>();
        for (GbGroupEntity g : groups) {
            nodes.put(g.getId(), new GroupNode(g, new ArrayList<>()));
        }
        List<GroupNode> roots = new ArrayList<>();
        for (GbGroupEntity g : groups) {
            GroupNode node = nodes.get(g.getId());
            GroupNode parent = nodes.get(g.getParentId());
            if (parent != null && parent != node) {
                parent.children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }

    private GbGroupEntity requireGroup(Long id) {
        GbGroupEntity g = selectValidGroup(id);
        if (g == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在: " + id);
        }
        return g;
    }

    /** 仅校验分组存在且未删（跨包用: 数据授权 groupIds 落库前校验, P0-3）; 非法/不存在→400 */
    public void requireGroupExists(Long id) {
        selectValidGroup(id);
    }

    /** id 合法性校验 + 查库（含未删校验）, 无效返回 null */
    private GbGroupEntity selectValidGroup(Long id) {
        if (id == null || id <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分组 id 非法: " + id);
        }
        GbGroupEntity g = groupMapper.selectById(id);
        if (g == null || g.getDeletedTime() != null) {
            return null;
        }
        return g;
    }

    private boolean isDescendant(GbGroupEntity candidate, GbGroupEntity ancestor) {
        String path = ancestor.getAncestors() + "," + ancestor.getId();
        return isUnderPrefix(candidate.getAncestors(), path);
    }

    /**
     * ancestors 是否落在给定前缀之下: 恰等于该前缀（直接子）或以"前缀,"开头（更深层后代）。
     * P0-1: 旧实现只匹配 startsWith(prefix + ",") 会漏掉直接子分组 —— 直接子的 ancestors
     * 恰好等于 oldPrefix（不带逗号后缀）, 挪挂载点时直接子不会被重写, 留下脏 ancestors。
     */
    private static boolean isUnderPrefix(String ancestors, String prefix) {
        return ancestors != null
                && (ancestors.equals(prefix) || ancestors.startsWith(prefix + ","));
    }

    private String requireName(String groupName) {
        if (!StringUtils.hasText(groupName)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分组名称不能为空");
        }
        return groupName.trim();
    }

    private static Set<Long> groupIds(List<GbGroupEntity> groups) {
        Set<Long> ids = new HashSet<>();
        for (GbGroupEntity g : groups) {
            ids.add(g.getId());
        }
        return ids;
    }

    // ---------------------------------------------------------------- DTO

    public record GroupNode(GbGroupEntity group, List<GroupNode> children) {
    }

    public record GroupChannelNode(GbGroupEntity group, List<GroupChannelNode> children,
            List<GbDeviceChannelEntity> channels) {
    }

    /** 绑定视图通道行: name=customName>name>channelId 展示名, boundGroups 为当前所属分组;
     *  directory=目录节点（前端组层级用, 只作级联勾选分组壳, 保存只取通道叶子） */
    public record BindViewChannel(Long id, String channelId, String name, Integer status,
            Integer ptzType, Long deviceDbId, String deviceName,
            List<Long> boundGroupIds, List<String> boundGroupNames, boolean directory) {
    }

    public record BindView(Long groupId, List<BindViewChannel> channels) {
    }
}
