package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 崩溃恢复单元测试（docs/06 §8）：纯 Mockito, 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    /** 与被测类 ACTIVE 口径一致 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private GbMediaServerMapper serverMapper;

    @Mock
    private ZlmApiClient zlm;

    @Mock
    private SessionCleanupService cleanup;

    @Mock
    private SsrcPoolService ssrcPool;

    @Mock
    private com.yujian.vision.service.ha.HaRuntimeService ha;

    private RecoveryService service;

    @BeforeEach
    void setUp() {
        service = spy(new RecoveryService(sessionMapper, serverMapper, zlm, cleanup, ssrcPool, ha));
        // 缺省活跃身份放行恢复; 门禁用例单独覆写
        org.mockito.Mockito.lenient().when(ha.mayRecoverAtBoot()).thenReturn(true);
    }

    @Test
    @DisplayName("HA 门禁: 备用身份启动(租约被持) → 跳过崩溃恢复(不清会话不清 SSRC 表)")
    void skipsRecoveryWhenStandbyBoot() {
        org.mockito.Mockito.lenient().when(ha.mayRecoverAtBoot()).thenReturn(false);

        assertDoesNotThrow(() -> service.onApplicationReady());

        org.mockito.Mockito.verify(service, never()).recover();
        org.mockito.Mockito.verifyNoInteractions(cleanup, ssrcPool);
    }

    @Test
    @DisplayName("recover: 逐条清理全部活跃态会话, 并重建节点 SSRC 占用表")
    void recoverCleansAllActiveSessionsAndRebuildsSsrc() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session(1L, "INVITING"), session(2L, "STREAMING")));
        when(serverMapper.selectList(any())).thenReturn(List.of(server("vision-zlm")));
        when(zlm.getMediaList("vision-zlm")).thenReturn("{}");

        service.recover();

        verify(cleanup, times(1)).closeAfterRestart(1L, "CLOSED", "platform_restart", "platform-restart", true);
        verify(cleanup, times(1)).closeAfterRestart(2L, "CLOSED", "platform_restart", "platform-restart", true);
        verify(ssrcPool, times(1)).resetInUse("vision-zlm");
    }

    @Test
    @DisplayName("recover: 无活跃会话时不做清理, 但仍重建 SSRC 占用表")
    void recoverWithNoActiveSessionStillRebuildsSsrc() {
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(serverMapper.selectList(any())).thenReturn(List.of(server("vision-zlm")));

        service.recover();

        verify(cleanup, never()).closeAfterRestart(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        verify(ssrcPool, times(1)).resetInUse("vision-zlm");
    }

    @Test
    @DisplayName("recover: 单条清理失败不中断, 继续清理下一条")
    void recoverContinuesWhenSingleCleanupFails() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session(1L, "INVITING"), session(2L, "STREAMING")));
        when(serverMapper.selectList(any())).thenReturn(List.of(server("vision-zlm")));
        doThrow(new RuntimeException("close failed")).when(cleanup)
                .closeAfterRestart(1L, "CLOSED", "platform_restart", "platform-restart", true);

        assertDoesNotThrow(() -> service.recover());

        verify(cleanup, times(1)).closeAfterRestart(2L, "CLOSED", "platform_restart", "platform-restart", true);
    }

    @Test
    @DisplayName("onApplicationReady: ApplicationReadyEvent 幂等守卫, 恢复只执行一次")
    void onApplicationReadyRunsRecoverOnlyOnce() {
        doNothing().when(service).recover();

        service.onApplicationReady();
        service.onApplicationReady();

        verify(service, times(1)).recover();
    }

    @Test
    @DisplayName("recover: ZLM 节点预检不可达时降级, closeAfterRestart 第5参=false 且仍重建 SSRC 占用表")
    void recoverSkipsZlmCleanupWhenNodeUnreachable() {
        when(sessionMapper.selectList(any())).thenReturn(List.of(
                session(1L, "STREAMING"), session(2L, "CLOSING")));
        when(serverMapper.selectList(any())).thenReturn(List.of(server("vision-zlm")));
        when(zlm.getMediaList("vision-zlm")).thenThrow(new RuntimeException("connect timeout"));

        service.recover();

        verify(cleanup, times(1)).closeAfterRestart(1L, "CLOSED", "platform_restart", "platform-restart", false);
        verify(cleanup, times(1)).closeAfterRestart(2L, "CLOSED", "platform_restart", "platform-restart", false);
        verify(ssrcPool, times(1)).resetInUse("vision-zlm");
    }

    @Test
    @DisplayName("buildStaleQuery: 活跃态 且 create_time < startupAt, 启动窗口内新点播由 SQL 排除(评审 R2)")
    void buildStaleQueryExcludesSessionsCreatedAfterStartup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GbMediaSessionEntity.class);
        LocalDateTime startupAt = LocalDateTime.of(2026, 9, 7, 12, 0);

        LambdaQueryWrapper<GbMediaSessionEntity> query = RecoveryService.buildStaleQuery(startupAt);

        String sql = query.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("status in"));
        assertTrue(sql.contains("create_time <"));
        assertTrue(query.getParamNameValuePairs().containsValue(startupAt));
        assertTrue(query.getParamNameValuePairs().values().containsAll(ACTIVE_STATUSES));
    }

    private GbMediaSessionEntity session(long id, String status) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(id);
        s.setStatus(status);
        s.setServerId("vision-zlm");
        return s;
    }

    private GbMediaServerEntity server(String serverId) {
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setServerId(serverId);
        return node;
    }
}
