package com.yujian.vision.service.system.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yujian.vision.service.record.entity.GbCloudRecordEntity;
import com.yujian.vision.service.record.mapper.GbCloudRecordMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 云端录像保留清理任务单测: 分布式锁/分批删/文件连带删除/wrapper 条件。
 * 纯 Mockito + 临时目录, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class CloudRecordRetentionTaskTest {

    @Mock
    private GbCloudRecordMapper recordMapper;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock lock;

    @TempDir
    Path tmp;

    private CloudRecordRetentionTask task() {
        return new CloudRecordRetentionTask(recordMapper, redisson, 30);
    }

    @Test
    @DisplayName("抢不到分布式锁 → 本轮直接放弃, 无任何交互")
    void skipsWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, TimeUnit.SECONDS)).thenReturn(false);

        task().reconcile();

        verifyNoInteractions(recordMapper);
    }

    @Test
    @DisplayName("分批清理: 选中行的落盘文件一并删除, 库行按批删除")
    void sweepDeletesFilesAndRows() throws IOException {
        Path keep = Files.createFile(tmp.resolve("old.mp4"));
        GbCloudRecordEntity r1 = new GbCloudRecordEntity();
        r1.setId(1L);
        r1.setFilePath(keep.toString());
        GbCloudRecordEntity r2 = new GbCloudRecordEntity();
        r2.setId(2L);
        r2.setFilePath(null);
        // 链式两次 thenReturn: 走单参重载, 避开泛型 varargs 隐式建数组（List<...>[]）的 unchecked
        when(recordMapper.selectList(any())).thenReturn(List.of(r1, r2)).thenReturn(List.of());
        when(recordMapper.deleteByIds(any())).thenReturn(2);

        task().doSweep();

        assertFalse(Files.exists(keep));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<java.io.Serializable>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(recordMapper).deleteByIds(captor.capture());
        assertTrue(captor.getValue().contains(1L));
        assertTrue(captor.getValue().contains(2L));
    }

    @Test
    @DisplayName("wrapper 条件: end_time < cutoff 或 (end_time 为空且 create_time < cutoff)")
    void wrapperCoversNullEndTime() {
        when(recordMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> task().doSweep());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<GbCloudRecordEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(recordMapper).selectList(captor.capture());
        String sql = ((QueryWrapper<GbCloudRecordEntity>) captor.getValue()).getSqlSegment().toLowerCase();
        assertTrue(sql.contains("end_time <"));
        assertTrue(sql.contains("end_time is null"));
        assertTrue(sql.contains("create_time <"));
    }

    @Test
    @DisplayName("单批异常不外抛(任务级兜底)")
    void sweepSwallowsFailure() {
        when(recordMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task().doSweep());
    }

    @Test
    @DisplayName("B3 P1 先删行后删文件: 行删失败 → 文件一个不动(避免文件删了行还在的悬挂)")
    void rowDeleteFailureLeavesFilesUntouched() throws IOException {
        Path keep = Files.createFile(tmp.resolve("still-here.mp4"));
        GbCloudRecordEntity r1 = new GbCloudRecordEntity();
        r1.setId(1L);
        r1.setFilePath(keep.toString());
        when(recordMapper.selectList(any())).thenReturn(List.of(r1)).thenReturn(List.of());
        when(recordMapper.deleteByIds(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> task().doSweep());

        assertTrue(Files.exists(keep), "行删除失败时文件不应被删");
    }
}
