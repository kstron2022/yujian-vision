package com.yujian.vision.service.device.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbGroupChannelRefEntity;
import com.yujian.vision.service.device.entity.GbGroupEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.mapper.GbGroupChannelRefMapper;
import com.yujian.vision.service.device.mapper.GbGroupMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分组管理单元测试（docs/09 M2 #5, B3 P0-4）：纯 Mockito, 不起 Spring 上下文/数据库。
 * 重点锁 P0-1: 挪挂载点后代 ancestors 重写必须覆盖直接子（ancestors 恰等于 oldPrefix,
 * 不带逗号后缀）与更深层后代两条路径 —— 旧实现只匹配 startsWith(oldPrefix + ",") 会漏直接子。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupManageServiceTest {

    @Mock
    private GbGroupMapper groupMapper;

    @Mock
    private GbGroupChannelRefMapper refMapper;

    @Mock
    private GbDeviceChannelMapper channelMapper;

    @Mock
    private GbDeviceMapper deviceMapper;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock treeLock;

    @Mock
    private TransactionTemplate txTemplate;

    private GroupManageService service;

    /** 四层树: A(1) > B(2) > C(3) > E(4), 另有根 D(5); ancestors 落库口径 "0,祖先id串" */
    private GbGroupEntity a;
    private GbGroupEntity b;
    private GbGroupEntity c;
    private GbGroupEntity e;
    private GbGroupEntity d;

    @BeforeAll
    static void initMpMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbGroupEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new GroupManageService(groupMapper, refMapper, channelMapper, deviceMapper, redisson, txTemplate);
        when(redisson.getLock("gb:lock:group-tree")).thenReturn(treeLock);
        stubLockAndTx();
        a = group(1L, 0L, "0");
        b = group(2L, 1L, "0,1");
        c = group(3L, 2L, "0,1,2");
        e = group(4L, 3L, "0,1,2,3");
        d = group(5L, 0L, "0");
    }

    private static GbGroupEntity group(Long id, Long parentId, String ancestors) {
        GbGroupEntity g = new GbGroupEntity();
        g.setId(id);
        g.setParentId(parentId);
        g.setAncestors(ancestors);
        g.setGroupName("G" + id);
        g.setSortOrder(0);
        return g;
    }

    /** 深拷贝防自污染: 被测方法会原地改 self, stub 返回共享实例会让循环读到已改值 */
    private static GbGroupEntity copy(GbGroupEntity g) {
        GbGroupEntity n = new GbGroupEntity();
        n.setId(g.getId());
        n.setParentId(g.getParentId());
        n.setAncestors(g.getAncestors());
        n.setGroupName(g.getGroupName());
        n.setSortOrder(g.getSortOrder());
        return n;
    }

    private void stubAllGroups() {
        when(groupMapper.selectList(any())).thenAnswer(
                inv -> new ArrayList<>(List.of(copy(a), copy(b), copy(c), copy(e), copy(d))));
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(groupMapper.selectById(2L)).thenReturn(b);
        when(groupMapper.selectById(3L)).thenReturn(c);
        when(groupMapper.selectById(4L)).thenReturn(e);
        when(groupMapper.selectById(5L)).thenReturn(d);
    }

    /** tryLock 结果桩: 受检异常就地消化 */
    private void stubTryLock(boolean result) {
        try {
            when(treeLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(result);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 锁与事务直通桩: tryLock 恒成功，事务回调就地执行（锁行为用例再覆写） */
    private void stubLockAndTx() {
        stubTryLock(true);
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
    }

    /** updateById 捕获 → id → ancestors(后写覆盖先写) */
    private Map<Long, String> ancestorsByUpdate() {
        ArgumentCaptor<GbGroupEntity> captor = ArgumentCaptor.forClass(GbGroupEntity.class);
        verify(groupMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        Map<Long, String> out = new LinkedHashMap<>();
        for (GbGroupEntity g : captor.getAllValues()) {
            out.put(g.getId(), g.getAncestors());
        }
        return out;
    }

    @Test
    @DisplayName("P0-1 挪挂载点: B 挪到新父 D → 直接子 C 与孙子 E 的 ancestors 均重写")
    void moveBranchRewritesDirectChildAndGrandchild() {
        stubAllGroups();

        service.update(2L, 5L, null, null);

        Map<Long, String> m = ancestorsByUpdate();
        // 自身 B: "0,1" → "0,5"
        assertEquals("0,5", m.get(2L));
        // 直接子 C（ancestors 恰等于 oldPrefix, 不带逗号 —— P0-1 旧实现漏改的就是它）
        assertEquals("0,5,2", m.get(3L));
        // 孙子 E（ancestors 以 "0,1,2," 开头）
        assertEquals("0,5,2,3", m.get(4L));
    }

    @Test
    @DisplayName("P0-1 挪到根: B 挪到根 → 直接子 C 与孙子 E 的 ancestors 均重写")
    void moveToRootRewritesDirectChildAndGrandchild() {
        stubAllGroups();

        service.update(2L, 0L, null, null);

        Map<Long, String> m = ancestorsByUpdate();
        assertEquals("0", m.get(2L));
        assertEquals("0,2", m.get(3L));
        assertEquals("0,2,3", m.get(4L));
    }

    @Test
    @DisplayName("P0-1 回归: 挪挂载点后无任何节点残留旧前缀 0,1,2")
    void noStalePrefixLeftAfterMove() {
        stubAllGroups();

        service.update(2L, 5L, null, null);

        Map<Long, String> m = ancestorsByUpdate();
        for (String s : m.values()) {
            assertFalse(s.startsWith("0,1,2"),
                    "挪挂载点后仍残留旧前缀 0,1,2: " + s);
        }
    }

    @Test
    @DisplayName("父分组=自身 → 400")
    void moveUnderSelfRejected() {
        stubAllGroups();

        BizException e = assertThrows(BizException.class, () -> service.update(2L, 2L, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("父分组=直接子 → 400")
    void moveUnderDirectChildRejected() {
        stubAllGroups();

        BizException e = assertThrows(BizException.class, () -> service.update(1L, 2L, null, null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("删除有子分组的分组 → 400")
    void deleteWithChildrenRejected() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(groupMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("删除仍绑定通道的分组 → 400")
    void deleteWithChannelsRejected() {
        when(groupMapper.selectById(3L)).thenReturn(c);
        when(groupMapper.selectCount(any())).thenReturn(0L);
        when(refMapper.selectCount(any())).thenReturn(2L);

        BizException e = assertThrows(BizException.class, () -> service.delete(3L));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(groupMapper, never()).updateById(any(GbGroupEntity.class));
    }

    @Test
    @DisplayName("绑定通道: 含无效/已删通道 → 400 且不解绑既有绑定")
    void bindChannelsWithInvalidChannelRejected() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(channelMapper.selectCount(any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> service.bindChannels(1L, List.of(11L, 99L)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(refMapper, never()).delete(any());
    }

    @Test
    @DisplayName("单分组约束: 目标通道已被其他分组绑定 → 400 列明分组且不动既有绑定")
    void bindChannelsWithCrossGroupConflictRejected() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(channelMapper.selectCount(any())).thenReturn(2L);
        // 冲突: 通道 11 已绑在分组 5(D)
        GbGroupChannelRefEntity conflict = new GbGroupChannelRefEntity();
        conflict.setGroupId(5L);
        conflict.setChannelDbId(11L);
        when(refMapper.selectList(any())).thenReturn(List.of(conflict));
        when(groupMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(copy(a), copy(d))));

        BizException e = assertThrows(BizException.class,
                () -> service.bindChannels(1L, List.of(11L, 12L)));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        // 文案带冲突分组名, 便于用户定位去哪解绑
        assertEquals(true, e.getMessage().contains("G5"));
        // 全量覆盖语义下尤其重要: 冲突必须在删本组绑定之前拦截
        verify(refMapper, never()).delete(any());
    }

    @Test
    @DisplayName("单分组约束: 无跨组冲突 → 正常全量覆盖(删旧绑定+逐条插入)")
    void bindChannelsWithoutConflictOverwrites() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(channelMapper.selectCount(any())).thenReturn(2L);
        // 注意: 冲突查询的 ne(groupId) 过滤在 SQL 层, mock any() 不区分 → 这里只验证无冲突路径
        when(refMapper.selectList(any())).thenReturn(List.of());

        service.bindChannels(1L, List.of(11L, 12L));

        verify(refMapper).delete(any());
        verify(refMapper, org.mockito.Mockito.times(2)).insert(any(GbGroupChannelRefEntity.class));
    }

    @Test
    @DisplayName("P0-2 channels: filter 非空时仅返回许可通道; null 不过滤")
    void channelsWithPredicateFilter() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        GbGroupChannelRefEntity r1 = new GbGroupChannelRefEntity();
        r1.setGroupId(1L);
        r1.setChannelDbId(11L);
        GbGroupChannelRefEntity r2 = new GbGroupChannelRefEntity();
        r2.setGroupId(1L);
        r2.setChannelDbId(12L);
        when(refMapper.selectList(any())).thenReturn(List.of(r1, r2));
        GbDeviceChannelEntity c11 = new GbDeviceChannelEntity();
        c11.setId(11L);
        GbDeviceChannelEntity c12 = new GbDeviceChannelEntity();
        c12.setId(12L);
        when(channelMapper.selectList(any())).thenReturn(List.of(c11, c12));

        // null = 不过滤: 两条都返回
        assertEquals(2, service.channels(1L).size());

        // 数据权限谓词: 仅 11 在许可集
        List<GbDeviceChannelEntity> out = service.channels(1L, ch -> ch.getId() == 11L);
        assertEquals(1, out.size());
        assertEquals(11L, out.get(0).getId());
    }

    @Test
    @DisplayName("树写互斥: 拿不到锁 → 忙拒绝且不触库")
    void treeWriteLockBusyRejected() {
        stubTryLock(false);

        BizException e = assertThrows(BizException.class, () -> service.create(0L, "X", null));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, e.getErrorCode());
        verify(groupMapper, never()).insert(any(GbGroupEntity.class));
    }

    @Test
    @DisplayName("树写互斥: 业务失败也必须放锁")
    void treeWriteLockUnlocksOnBusinessFailure() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(groupMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.delete(1L));
        verify(treeLock).unlock();
    }

    @Test
    @DisplayName("树写互斥: 先拿锁后触库（互斥窗口覆盖整个事务）")
    void treeWriteLockAcquiredBeforeDbWrite() throws Exception {
        stubAllGroups();

        service.update(2L, 5L, null, null);

        InOrder ord = inOrder(treeLock, groupMapper);
        ord.verify(treeLock).tryLock(anyLong(), any(TimeUnit.class));
        // 自身 + 后代重写共多次 updateById, 只验"全部发生在拿锁之后"
        ord.verify(groupMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(GbGroupEntity.class));
    }

    @Test
    @DisplayName("bindView: 目录节点保留但打 directory 标, 普通通道不打")
    void bindViewMarksDirectoryChannels() {
        when(groupMapper.selectById(1L)).thenReturn(a);
        when(groupMapper.selectList(any())).thenReturn(List.of());
        when(refMapper.selectList(any())).thenReturn(List.of());
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        GbDeviceChannelEntity normal = new GbDeviceChannelEntity();
        normal.setId(11L);
        normal.setChannelId("34020000001310000001");
        normal.setName("cam");
        normal.setParentId("13010100002000000110"); // 挂在平台根下
        GbDeviceChannelEntity reported = new GbDeviceChannelEntity(); // 上报式目录: parental=1
        reported.setId(12L);
        reported.setChannelId("420202");
        reported.setParental(1);
        GbDeviceChannelEntity structural = new GbDeviceChannelEntity(); // 漏报式目录: 是 normal 的 parent
        structural.setId(13L);
        structural.setChannelId("13010100002000000110");
        when(channelMapper.selectList(any())).thenReturn(List.of(normal, reported, structural));

        GroupManageService.BindView view = service.bindView(1L, null);

        assertEquals(3, view.channels().size());
        assertFalse(view.channels().get(0).directory(), "普通通道不打标");
        assertTrue(view.channels().get(1).directory(), "上报 parental=1 → 目录");
        assertTrue(view.channels().get(2).directory(), "是其它通道的 parent → 目录");
    }
}
