package com.yujian.vision.service.record;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.record.entity.GbCloudRecordEntity;
import com.yujian.vision.service.record.mapper.GbCloudRecordMapper;
import java.time.Duration;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * 云端录像单元测试: on_record_mp4 归因（活跃会话命中/孤儿丢弃）、按计划触发录制、
 * 下载 token 一次性消费。纯 Mockito, 不起 Spring 上下文/数据库/Redis。
 */
@ExtendWith(MockitoExtension.class)
class CloudRecordServiceTest {

    @BeforeAll
    static void initMpMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GbCloudRecordEntity.class);
        TableInfoHelper.initTableInfo(assistant, GbMediaSessionEntity.class);
    }

    @Mock
    private GbCloudRecordMapper recordMapper;

    @Mock
    private GbMediaSessionMapper sessionMapper;

    @Mock
    private RecordPlanService plans;

    @Mock
    private ZlmApiClient zlm;

    @Mock
    private RedissonClient redisson;

    @Mock
    private RBucket<String> bucket;

    private CloudRecordService service;

    @BeforeEach
    void setUp() {
        service = new CloudRecordService(recordMapper, sessionMapper, plans, zlm, redisson,
                new ObjectMapper());
    }

    private static GbMediaSessionEntity session(int type) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(1L);
        s.setSessionType(type);
        s.setStreamId("gb_play_34020000001310000001");
        s.setApp("rtp");
        s.setDeviceId("34020000001320000001");
        s.setChannelId("34020000001310000001");
        s.setDeviceDbId(11L);
        s.setChannelDbId(22L);
        s.setServerId("zlm-1");
        s.setStatus("STREAMING");
        return s;
    }

    private static CloudRecordService.HookMp4Chunk chunk(String streamId) {
        return new CloudRecordService.HookMp4Chunk(streamId, "rtp", "zlm-1",
                "/data/record/rtp/2026-09-09/10-00-00.mp4", "10-00-00.mp4", 1913597L, 60.4D,
                1789000000L);
    }

    // ---------------------------------------------------------------- 归因入库

    @Test
    @DisplayName("on_record_mp4: 命中活跃会话 → 归因入库(通道/设备/编码/节点冗余)")
    void saveFromHookAttributesViaActiveSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_LIVE));

        boolean saved = service.saveFromHook(chunk("gb_play_34020000001310000001"));

        assertTrue(saved);
        ArgumentCaptor<GbCloudRecordEntity> captor = ArgumentCaptor.forClass(GbCloudRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        GbCloudRecordEntity row = captor.getValue();
        assertEquals(22L, row.getChannelDbId());
        assertEquals("34020000001310000001", row.getChannelId());
        assertEquals("34020000001320000001", row.getDeviceId());
        assertEquals("zlm-1", row.getServerId());
        assertEquals("/data/record/rtp/2026-09-09/10-00-00.mp4", row.getFilePath());
        assertEquals("10-00-00.mp4", row.getFileName());
        assertEquals(1913597L, row.getFileSize());
        assertEquals(60, row.getDuration());
        assertEquals(0, row.getUploadState());
        assertNotNull(row.getStartTime());
        assertNotNull(row.getEndTime());
    }

    @Test
    @DisplayName("on_record_mp4: 孤儿分片(无活跃会话) → 丢弃不入库")
    void saveFromHookDropsOrphanChunk() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        boolean saved = service.saveFromHook(chunk("gb_play_orphan"));

        assertFalse(saved);
        verify(recordMapper, never()).insert(any(GbCloudRecordEntity.class));
    }

    @Test
    @DisplayName("on_record_mp4: 无 streamId → 直接丢弃")
    void saveFromHookIgnoresBlankStream() {
        assertFalse(service.saveFromHook(chunk(null)));
        assertFalse(service.saveFromHook(null));
        verifyNoInteractions(recordMapper);
    }

    // ---------------------------------------------------------------- 按计划触发

    @Test
    @DisplayName("流就绪: 计划应录 → 调 ZLM startRecord(type=1)")
    void onStreamReadyStartsRecordingWhenDue() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_LIVE));
        when(plans.isRecordingDue(eq(22L), any())).thenReturn(true);

        service.onStreamReady("gb_play_34020000001310000001");

        verify(zlm).startRecord("zlm-1", "rtp", "gb_play_34020000001310000001", 1);
    }

    @Test
    @DisplayName("流就绪: 计划不应录 → 不调 ZLM")
    void onStreamReadySkipsWhenNotDue() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_LIVE));
        when(plans.isRecordingDue(eq(22L), any())).thenReturn(false);

        service.onStreamReady("gb_play_34020000001310000001");

        verifyNoInteractions(zlm);
    }

    @Test
    @DisplayName("流就绪: 同一流短窗内重复 regist → 只调一次 ZLM(多 schema 去重)")
    void onStreamReadyDedupesRepeatedRegist() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_LIVE));
        when(plans.isRecordingDue(eq(22L), any())).thenReturn(true);

        service.onStreamReady("gb_play_34020000001310000001");
        service.onStreamReady("gb_play_34020000001310000001");
        service.onStreamReady("gb_play_34020000001310000001");

        verify(zlm, times(1)).startRecord(anyString(), anyString(), anyString(), eq(1));
    }

    @Test
    @DisplayName("流就绪: 回放会话不参与计划录制")
    void onStreamReadySkipsPlaybackSession() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_PLAYBACK));

        service.onStreamReady("gb_playback_1");

        verifyNoInteractions(zlm, plans);
    }

    @Test
    @DisplayName("流就绪: 无活跃会话 → 静默返回")
    void onStreamReadyIgnoresUnknownStream() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        service.onStreamReady("unknown");

        verifyNoInteractions(zlm, plans);
    }

    // ---------------------------------------------------------------- 下载 token

    @Test
    @DisplayName("token: 签发 → 首次消费成功, 二次消费(重放)为 null")
    void tokenIsOneShot() {
        GbCloudRecordEntity row = new GbCloudRecordEntity();
        row.setId(7L);
        when(recordMapper.selectById(7L)).thenReturn(row);
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);

        String token = service.createDownloadToken(7L, 42L);

        assertNotNull(token);
        // 签发: 60s TTL
        verify(bucket).set(anyString(), eq(Duration.ofSeconds(CloudRecordService.DL_TOKEN_TTL_SECONDS)));

        // 首次消费: getAndDelete 返回载荷 → 成功
        when(bucket.getAndDelete()).thenReturn(
                "{\"recordId\":7,\"userId\":42}", (String) null);
        CloudRecordService.DownloadTicket first = service.consumeDownloadToken(token);
        assertNotNull(first);
        assertEquals(7L, first.recordId());
        assertEquals(42L, first.userId());

        // 二次消费(重放): 键已删 → null
        assertNull(service.consumeDownloadToken(token));
    }

    @Test
    @DisplayName("token: 过期/伪造/载荷非法 → null")
    void tokenInvalidReturnsNull() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn(null);
        assertNull(service.consumeDownloadToken("nope"));
        assertNull(service.consumeDownloadToken(""));
        assertNull(service.consumeDownloadToken(null));

        when(bucket.getAndDelete()).thenReturn("not-json");
        assertNull(service.consumeDownloadToken("bad"));
    }

    @Test
    @DisplayName("下载解析: 文件缺失 → 404 语义(不回滚已消费 token)")
    void resolveDownloadThrowsWhenFileMissing() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn("{\"recordId\":7,\"userId\":42}");
        GbCloudRecordEntity row = new GbCloudRecordEntity();
        row.setId(7L);
        row.setChannelDbId(22L);
        row.setFilePath("");
        when(recordMapper.selectById(7L)).thenReturn(row);

        assertThrows(BizException.class, () -> service.resolveDownload("t1"));
    }

    @Test
    @DisplayName("下载解析: 路径含 .. → 403 语义")
    void resolveDownloadRejectsTraversalPath() {
        when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn("{\"recordId\":7,\"userId\":42}");
        GbCloudRecordEntity row = new GbCloudRecordEntity();
        row.setId(7L);
        row.setChannelDbId(22L);
        row.setFilePath("/data/record/../../etc/passwd");
        when(recordMapper.selectById(7L)).thenReturn(row);

        assertThrows(BizException.class, () -> service.resolveDownload("t2"));
    }

    // ---------------------------------------------------------------- 删除

    @Test
    @DisplayName("删除: 文件缺失 → 仅删行, 不抛")
    void deleteToleratesMissingFile() {
        GbCloudRecordEntity row = new GbCloudRecordEntity();
        row.setId(7L);
        row.setFilePath("/data/record/not-exists.mp4");
        when(recordMapper.selectById(7L)).thenReturn(row);

        assertDoesNotThrow(() -> service.delete(7L));
        verify(recordMapper).deleteById(7L);
    }

    @Test
    @DisplayName("删除: 记录不存在 → 404 语义")
    void deleteMissingRowThrows() {
        when(recordMapper.selectById(9L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.delete(9L));
    }

    // ---------------------------------------------------------------- B3 补充

    @Test
    @DisplayName("on_record_mp4 归因口径: CLOSING 会话仍属活跃 → 命中入库, 且活跃集合含 CLOSING 不含终态")
    void saveFromHookAttributesClosingSession() {
        GbMediaSessionEntity closing = session(GbMediaSessionEntity.TYPE_LIVE);
        closing.setStatus("CLOSING");
        when(sessionMapper.selectOne(any())).thenReturn(closing);

        assertTrue(service.saveFromHook(chunk("gb_play_34020000001310000001")));
        verify(recordMapper).insert(any(GbCloudRecordEntity.class));

        // 口径锁定: 归因查询 status IN 集合含 CLOSING、不含终态 CLOSED
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<GbMediaSessionEntity>> wcaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sessionMapper).selectOne(wcaptor.capture());
        // 先渲染 SQL 片段: MP 的 IN 参数在片段渲染时才登记进 paramNameValuePairs
        wcaptor.getValue().getSqlSegment();
        var params = wcaptor.getValue().getParamNameValuePairs().values().toString();
        assertTrue(params.contains("CLOSING"));
        assertFalse(params.contains("CLOSED"));
    }

    @Test
    @DisplayName("page 列口径: select 显式排除 file_path, 排序 start_time DESC")
    void pageSelectExcludesFilePathAndOrdersByStartTime() {
        when(recordMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(22L, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<GbCloudRecordEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(recordMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<GbCloudRecordEntity> qw =
                (LambdaQueryWrapper<GbCloudRecordEntity>) captor.getValue();
        String select = qw.getSqlSelect().toLowerCase();
        assertTrue(select.contains("start_time"));
        // 服务器路径不外泄: 出参列不含 file_path
        assertFalse(select.contains("file_path"));
        assertTrue(qw.getSqlSegment().toLowerCase().contains("order by start_time desc"));
    }

    @Test
    @DisplayName("page 数据权限: 许可集非空 → channel_db_id IN; 空集=全禁 → 空页且不下 SQL")
    void pageScopeFiltering() {
        when(recordMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(22L, Set.of(1L, 2L), null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<GbCloudRecordEntity>> captor =
                ArgumentCaptor.forClass(Wrapper.class);
        verify(recordMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("channel_db_id in"), "应按许可集收口: " + sql);

        // 全禁: 短路返回空页, 不打库
        var empty = service.page(22L, Set.of(), null, null, 1, 20);
        assertEquals(0, empty.getTotal());
        assertTrue(empty.getRecords().isEmpty());
        verify(recordMapper, times(1)).selectPage(any(), any());
    }

    @Test
    @DisplayName("B3 P1: 流注销清 startRecord 去重条目 → 同 streamId 再点播可再次按计划开录")
    void onStreamClosedClearsDedupeEntry() {
        when(sessionMapper.selectOne(any())).thenReturn(session(GbMediaSessionEntity.TYPE_LIVE));
        when(plans.isRecordingDue(eq(22L), any())).thenReturn(true);
        service.onStreamReady("gb_play_34020000001310000001");
        verify(zlm, times(1)).startRecord(anyString(), anyString(), anyString(), eq(1));

        // 关流清条目后, 同一流再次就绪仍应再次开录（若无清理则被去重窗压制）
        service.onStreamClosed("gb_play_34020000001310000001");
        service.onStreamReady("gb_play_34020000001310000001");
        verify(zlm, times(2)).startRecord(anyString(), anyString(), anyString(), eq(1));
    }
}
