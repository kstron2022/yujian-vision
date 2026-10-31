package com.yujian.vision.service.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbGroupChannelRefEntity;
import com.yujian.vision.service.device.mapper.GbGroupChannelRefMapper;
import com.yujian.vision.service.system.entity.SysRoleChannelEntity;
import com.yujian.vision.service.system.entity.SysRoleEntity;
import com.yujian.vision.service.system.entity.SysRoleGroupEntity;
import com.yujian.vision.service.system.mapper.SysRoleChannelMapper;
import com.yujian.vision.service.system.mapper.SysRoleGroupMapper;
import com.yujian.vision.service.system.mapper.SysRoleMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 通道级数据权限（docs/09 M2 #5 通道级授权）。
 * <p>规则: admin 角色恒全部; data_scope=1 全部; =2 取"角色-分组（子树经 gb_group 展开为自身+全部后代）
 * ∪ 角色-通道直选"的通道 id 集。按用户做 5s TTL 解析缓存（B6 性能专项: 点播/列表/会话热路径
 * 每请求省 3~5 次 DB 查询; 角色/授权变更最迟 5s 生效, 与 hook secret 缓存同阶）。
 * <p>userId == null 视为内部调用方, 返回 ALL（均为登录态接口, userId 由当前登录用户注入, 正常请求不会为 null）。
 * <p>拦截点口径（B3 P0-2 收口后）: 通道列表/通道树、分组通道树/分组通道列表、实时点播、回放点播、
 * 回放控制、云台控制（含预置位列表）、录像检索、云端录像列表/下载/删除、录像计划、会话列表。
 * 上述端点均已接 resolve/checkAccess/channelFilter, 后续新增通道敏感接口必须同样接入。
 */
@Service
@RequiredArgsConstructor
public class ChannelDataScopeService {

    private final SysRoleMapper roleMapper;
    private final SysRoleGroupMapper roleGroupMapper;
    private final SysRoleChannelMapper roleChannelMapper;
    private final GbGroupChannelRefMapper refMapper;
    private final GroupManageService groupService;

    /** 过滤条件: all=true 不限制; 否则仅 ids 内的通道可见 */
    public record ScopeFilter(boolean all, Set<Long> ids) {

        public static ScopeFilter ALL() {
            return new ScopeFilter(true, Set.of());
        }
    }

    // ---------------------------------------------------------------- 解析

    /** scope 解析缓存 TTL（B6 性能专项）: 角色/授权变更最迟 5s 生效 */
    private static final long SCOPE_CACHE_TTL_MS = 5_000;
    /** 缓存容量上限（防异常膨胀; 用户量远小于此, 触顶整体失效重来） */
    private static final int SCOPE_CACHE_MAX = 10_000;

    private record ScopeCacheEntry(ScopeFilter filter, long expireAt) {
    }

    private final java.util.Map<Long, ScopeCacheEntry> scopeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 解析用户可见通道集（带 5s TTL 缓存, 解析规则见 {@link #resolveDb(Long)}）。
     */
    public ScopeFilter resolve(Long userId) {
        if (userId == null) {
            return ScopeFilter.ALL();
        }
        long now = System.currentTimeMillis();
        ScopeCacheEntry hit = scopeCache.get(userId);
        if (hit != null && now < hit.expireAt()) {
            return hit.filter();
        }
        if (scopeCache.size() >= SCOPE_CACHE_MAX) {
            scopeCache.clear();
        }
        ScopeFilter filter = resolveDb(userId);
        scopeCache.put(userId, new ScopeCacheEntry(filter, now + SCOPE_CACHE_TTL_MS));
        return filter;
    }

    /** 权限/授权变更后调用可立即生效（当前未接入变更点, 依赖 TTL 兜底） */
    public void evict(Long userId) {
        scopeCache.remove(userId);
    }

    /**
     * 解析用户可见通道集, 步骤:
     * <ol>
     * <li>userId == null → ALL（内部调用方）;</li>
     * <li>任一角色 admin 或 data_scope=1/null → ALL;</li>
     * <li>收集 data_scope=2 角色id → sys_role_group 得分组 → expandWithDescendants 展开子树
     * → gb_group_channel_ref 得分组下通道;</li>
     * <li>UNION sys_role_channel 直选通道;</li>
     * <li>两处授权均为空 → 全禁（empty ids）; 无角色同全禁。</li>
     * </ol>
     */
    private ScopeFilter resolveDb(Long userId) {
        List<SysRoleEntity> roles = roleMapper.selectRolesByUserId(userId);
        Set<Long> limitedRoleIds = new HashSet<>();
        for (SysRoleEntity r : roles) {
            if ("admin".equals(r.getRoleCode()) || r.getDataScope() == null || r.getDataScope() == 1) {
                return ScopeFilter.ALL();
            }
            if (r.getDataScope() == 2) {
                limitedRoleIds.add(r.getId());
            }
        }
        if (limitedRoleIds.isEmpty()) {
            // 全部角色都是受限范围但无可授权行（或用户无角色）→ 全禁
            return new ScopeFilter(false, Set.of());
        }
        Set<Long> permitted = new HashSet<>();
        Set<Long> groupIds = new HashSet<>();
        for (SysRoleGroupEntity rg : roleGroupMapper.selectList(
                new LambdaQueryWrapper<SysRoleGroupEntity>()
                        .in(SysRoleGroupEntity::getRoleId, limitedRoleIds))) {
            groupIds.add(rg.getGroupId());
        }
        if (!groupIds.isEmpty()) {
            Set<Long> expanded = groupService.expandWithDescendants(groupIds);
            for (GbGroupChannelRefEntity ref : refMapper.selectList(
                    new LambdaQueryWrapper<GbGroupChannelRefEntity>()
                            .in(GbGroupChannelRefEntity::getGroupId, expanded))) {
                permitted.add(ref.getChannelDbId());
            }
        }
        for (SysRoleChannelEntity rc : roleChannelMapper.selectList(
                new LambdaQueryWrapper<SysRoleChannelEntity>()
                        .in(SysRoleChannelEntity::getRoleId, limitedRoleIds))) {
            permitted.add(rc.getChannelDbId());
        }
        return new ScopeFilter(false, permitted);
    }

    // ---------------------------------------------------------------- 判定

    /** 通道是否在用户数据范围内 */
    public boolean canAccess(Long channelDbId, Long userId) {
        ScopeFilter filter = resolve(userId);
        return filter.all() || filter.ids().contains(channelDbId);
    }

    /** 不在数据范围内时抛 403 */
    public void checkAccess(Long channelDbId, Long userId) {
        if (!canAccess(channelDbId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无该通道的数据权限");
        }
    }

    /** 列表过滤谓词（配合循环/Stream 过滤通道列表） */
    public Predicate<GbDeviceChannelEntity> channelFilter(Long userId) {
        ScopeFilter filter = resolve(userId);
        return c -> filter.all() || filter.ids().contains(c.getId());
    }
}
