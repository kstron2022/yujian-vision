package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 通道级数据权限单元测试（docs/09 M2 #5, B3 P0-4）: 纯 Mockito, 不起 Spring 上下文/数据库。
 * 覆盖: admin 短路 / data_scope=1 全量 / scope=2 多角色并集（子树展开直通）/ scope=2 无授权全禁 /
 * userId=null 视内部调用 / canAccess-checkAccess / channelFilter 谓词。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelDataScopeServiceTest {

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SysRoleGroupMapper roleGroupMapper;

    @Mock
    private SysRoleChannelMapper roleChannelMapper;

    @Mock
    private GbGroupChannelRefMapper refMapper;

    @Mock
    private GroupManageService groupService;

    private ChannelDataScopeService service;

    @BeforeEach
    void setUp() {
        service = new ChannelDataScopeService(roleMapper, roleGroupMapper, roleChannelMapper,
                refMapper, groupService);
    }

    private static SysRoleEntity role(long id, String code, Integer dataScope) {
        SysRoleEntity r = new SysRoleEntity();
        r.setId(id);
        r.setRoleCode(code);
        r.setDataScope(dataScope);
        return r;
    }

    private static SysRoleGroupEntity rg(long roleId, long groupId) {
        SysRoleGroupEntity e = new SysRoleGroupEntity();
        e.setRoleId(roleId);
        e.setGroupId(groupId);
        return e;
    }

    private static SysRoleChannelEntity rc(long roleId, long channelId) {
        SysRoleChannelEntity e = new SysRoleChannelEntity();
        e.setRoleId(roleId);
        e.setChannelDbId(channelId);
        return e;
    }

    private static GbGroupChannelRefEntity ref(long groupId, long channelId) {
        GbGroupChannelRefEntity e = new GbGroupChannelRefEntity();
        e.setGroupId(groupId);
        e.setChannelDbId(channelId);
        return e;
    }

    private static GbDeviceChannelEntity channel(long id) {
        GbDeviceChannelEntity c = new GbDeviceChannelEntity();
        c.setId(id);
        return c;
    }

    /** 子树展开 mock 直通: 原样返回入参（展开逻辑由 GroupManageServiceTest 另测） */
    private void stubExpandPassthrough() {
        when(groupService.expandWithDescendants(any()))
                .thenAnswer(inv -> new HashSet<>(inv.<Collection<Long>>getArgument(0)));
    }

    // ---------------------------------------------------------------- 解析

    @Test
    @DisplayName("userId=null → 内部调用方, 恒 ALL")
    void nullUserIsAll() {
        ChannelDataScopeService.ScopeFilter f = service.resolve(null);
        assertTrue(f.all());
        assertTrue(f.ids().isEmpty());
        verifyNoInteractions(roleMapper, roleGroupMapper, roleChannelMapper, refMapper);
    }

    @Test
    @DisplayName("admin 角色 → 短路 ALL, 不再查分组/通道授权")
    void adminRoleShortCircuitsToAll() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(role(1L, "admin", 2)));

        ChannelDataScopeService.ScopeFilter f = service.resolve(7L);

        assertTrue(f.all());
        verifyNoInteractions(roleGroupMapper, refMapper, roleChannelMapper);
    }

    @Test
    @DisplayName("data_scope=1 角色 → 全量; data_scope=null 同样按全量")
    void dataScopeOneOrNullIsAll() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(role(1L, "operator", 1)));

        assertTrue(service.resolve(7L).all());

        when(roleMapper.selectRolesByUserId(8L)).thenReturn(List.of(role(2L, "viewer", null)));
        assertTrue(service.resolve(8L).all());
    }

    @Test
    @DisplayName("多角色 scope=2 → 分组∪直选并集; 子树展开直通")
    void multipleScopeTwoRolesUnion() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(
                role(10L, "role-a", 2), role(20L, "role-b", 2)));
        when(roleGroupMapper.selectList(any())).thenReturn(List.of(
                rg(10L, 100L), rg(10L, 101L), rg(20L, 102L)));
        stubExpandPassthrough();
        // 分组 100 → 通道1, 分组 102 → 通道2（分组 101 无通道也合法）
        when(refMapper.selectList(any())).thenReturn(List.of(ref(100L, 1L), ref(102L, 2L)));
        // 角色直选: 通道9
        when(roleChannelMapper.selectList(any())).thenReturn(List.of(rc(10L, 9L)));

        ChannelDataScopeService.ScopeFilter f = service.resolve(7L);

        assertFalse(f.all());
        assertEquals(Set.of(1L, 2L, 9L), f.ids());
    }

    @Test
    @DisplayName("scope=2 但分组/直选授权均为空 → 全禁（empty ids, 非 ALL）")
    void scopeTwoWithNoGrantsIsAllDenied() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(role(10L, "role-a", 2)));
        when(roleGroupMapper.selectList(any())).thenReturn(List.of());
        when(roleChannelMapper.selectList(any())).thenReturn(List.of());

        ChannelDataScopeService.ScopeFilter f = service.resolve(7L);

        assertFalse(f.all());
        assertTrue(f.ids().isEmpty());
    }

    @Test
    @DisplayName("用户无任何角色 → 全禁")
    void noRolesIsAllDenied() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of());

        ChannelDataScopeService.ScopeFilter f = service.resolve(7L);

        assertFalse(f.all());
        assertTrue(f.ids().isEmpty());
    }

    // ---------------------------------------------------------------- 判定

    @Test
    @DisplayName("canAccess: ALL 恒真; 受限集内外各一态; checkAccess 越权抛 FORBIDDEN")
    void canAccessAndCheckAccess() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(role(10L, "role-a", 2)));
        when(roleGroupMapper.selectList(any())).thenReturn(List.of(rg(10L, 100L)));
        stubExpandPassthrough();
        when(refMapper.selectList(any())).thenReturn(List.of(ref(100L, 1L), ref(100L, 2L)));
        when(roleChannelMapper.selectList(any())).thenReturn(List.of());

        // 许可集 {1,2}
        assertTrue(service.canAccess(1L, 7L));
        assertTrue(service.canAccess(2L, 7L));
        assertFalse(service.canAccess(3L, 7L));
        BizException e = assertThrows(BizException.class, () -> service.checkAccess(3L, 7L));
        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        // 许可集内不抛
        service.checkAccess(1L, 7L);
    }

    @Test
    @DisplayName("channelFilter: 许可集内 true / 集 false; ALL 时恒 true")
    void channelFilterPredicate() {
        when(roleMapper.selectRolesByUserId(7L)).thenReturn(List.of(role(10L, "role-a", 2)));
        when(roleGroupMapper.selectList(any())).thenReturn(List.of(rg(10L, 100L)));
        stubExpandPassthrough();
        when(refMapper.selectList(any())).thenReturn(List.of(ref(100L, 1L)));
        when(roleChannelMapper.selectList(any())).thenReturn(List.of());

        var predicate = service.channelFilter(7L);
        assertTrue(predicate.test(channel(1L)));
        assertFalse(predicate.test(channel(99L)));

        // ALL: 恒真（admin 用户）
        when(roleMapper.selectRolesByUserId(1L)).thenReturn(List.of(role(1L, "admin", 2)));
        assertTrue(service.channelFilter(1L).test(channel(99L)));
    }
}
