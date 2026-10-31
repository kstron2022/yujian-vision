package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

/** 跨节点拉流代理单测（M3-B6 阶段1） */
class StreamProxyServiceTest {

    private final String STREAM = "gb_play_x";
    private final String SRC = "vision-zlm";
    private final String DST = "vision-zlm2";

    private GbMediaSessionMapper sessionMapper;
    private GbMediaServerMapper serverMapper;
    private ZlmApiClient zlm;
    private PlayUrlService urlService;
    private RedissonClient redisson;
    private RMap<String, String> idx;
    private StreamProxyService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionMapper = Mockito.mock(GbMediaSessionMapper.class);
        serverMapper = Mockito.mock(GbMediaServerMapper.class);
        zlm = Mockito.mock(ZlmApiClient.class);
        urlService = Mockito.mock(PlayUrlService.class);
        redisson = Mockito.mock(RedissonClient.class);
        idx = Mockito.mock(RMap.class);
        // 宽匹配在前, specific 在后(Mockito 最近注册优先): 按需索引与常驻索引(M5-B1)分 key——
        // cleanupForStream 读常驻索引决定豁免, 同一 mock 会让常驻条目错误命中豁免清单
        org.mockito.Mockito.lenient()
                .doReturn(Mockito.mock(RMap.class)).when(redisson).getMap(anyString());
        doReturn(idx).when(redisson).getMap(StreamProxyService.IDX_PREFIX + STREAM);
        svc = new StreamProxyService(sessionMapper, serverMapper, zlm, urlService, redisson);

        GbMediaServerEntity src = node(SRC, 1);
        GbMediaServerEntity dst = node(DST, 1);
        when(serverMapper.selectOne(any())).thenReturn(src, dst);
    }

    private static GbMediaServerEntity node(String id, int status) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(id);
        n.setStatus(status);
        n.setHostIp("10.0.0.9");
        n.setHttpPort(8081);
        return n;
    }

    private GbMediaSessionEntity streaming(String serverId) {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(7L);
        s.setStreamId(STREAM);
        s.setServerId(serverId);
        s.setStatus("STREAMING");
        return s;
    }

    private void stubStreaming(String serverId) {
        when(sessionMapper.selectOne(any())).thenReturn(streaming(serverId));
        when(urlService.buildUrls(anyString(), anyString(), eq(3600L))).thenReturn(Map.of("flv", "u"));
    }

    @Test
    @DisplayName("无 STREAMING 会话抛 NOT_FOUND")
    void noStreamingSessionThrows() {
        when(sessionMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> svc.ensureProxy(STREAM, DST));
    }

    @Test
    @DisplayName("目标=源节点: 直接返回源地址, 不建代理")
    void sameNodeShortCircuit() {
        stubStreaming(SRC);
        Map<String, Object> r = svc.ensureProxy(STREAM, SRC);
        assertEquals(false, r.get("proxied"));
        verify(zlm, never()).addStreamProxy(anyString(), any());
    }

    @Test
    @DisplayName("目标不在线抛业务异常")
    void offlineTargetThrows() {
        stubStreaming(SRC);
        when(serverMapper.selectOne(any())).thenReturn(node(SRC, 1), node(DST, 0));
        assertThrows(BizException.class, () -> svc.ensureProxy(STREAM, DST));
    }

    @Test
    @DisplayName("跨节点: addStreamProxy 成功并登记索引")
    void happyPathCreatesProxy() {
        stubStreaming(SRC);
        when(idx.get(DST)).thenReturn(null);
        when(zlm.addStreamProxy(eq(DST), any())).thenReturn("k1");
        Map<String, Object> r = svc.ensureProxy(STREAM, DST);
        assertEquals(true, r.get("proxied"));
        verify(zlm).addStreamProxy(eq(DST), any());
        verify(idx).put(DST, "k1");
    }

    @Test
    @DisplayName("索引命中: 幂等跳过重建")
    void idempotentSkipsRecreate() {
        stubStreaming(SRC);
        when(idx.get(DST)).thenReturn("k1");
        Map<String, Object> hit = svc.ensureProxy(STREAM, DST);
        assertEquals(false, hit.get("proxied"));
        verify(zlm, never()).addStreamProxy(anyString(), any());
    }

    @Test
    @DisplayName("Redis 丢失后 ZLM 已有同名代理: 视为成功记 ?")
    void conflictFallbackWhenStreamAlreadyThere() {
        stubStreaming(SRC);
        when(idx.get(DST)).thenReturn(null);
        when(zlm.addStreamProxy(eq(DST), any())).thenThrow(
                new BizException(ErrorCode.ZLM_API_FAILED, "exists"));
        when(zlm.listMedia(DST)).thenReturn(List.of(new com.yujian.vision.media.zlm.ZlmMediaInfo("rtp", STREAM, 1, null, null, null)));
        Map<String, Object> r = svc.ensureProxy(STREAM, DST);
        assertEquals(true, r.get("proxied"));
        verify(idx).put(DST, "?");
    }

    @Test
    @DisplayName("cleanupForStream: 已知 key 走 delStreamProxy, 未知 key 走 closeStreams")
    void cleanupRemovesProxies() {
        when(idx.readAllMap()).thenReturn(Map.of(
                DST, "k1",
                "vision-zlm3", "?"));
        svc.cleanupForStream(STREAM);
        verify(zlm).delStreamProxy(DST, "k1");
        verify(zlm).closeStreams("vision-zlm3", "rtp", STREAM);
        verify(idx).delete();
    }

    @Test
    @DisplayName("cleanupForStream: 空索引直接返回")
    void cleanupEmptyIndexNoop() {
        when(idx.readAllMap()).thenReturn(Map.of());
        svc.cleanupForStream(STREAM);
        verify(zlm, never()).delStreamProxy(anyString(), anyString());
        verify(idx, never()).delete();
    }

    @Test
    @DisplayName("cleanupForStream 常驻豁免(M5-B1): 常驻节点跳过拆除, 非常驻节点正常拆")
    void cleanupExemptsAlwaysOnEntries() {
        // 常驻索引: n1 登记常驻; 按需索引: n1(与常驻同节点, 应豁免) + DST(跨节点分发代理, 应拆)
        // raw mock 泛型收窄: 强转+局部抑制(仅测试桩面, 同 setUp 的 idx 口径)
        @SuppressWarnings("unchecked")
        org.redisson.api.RMap<String, String> alwaysMap =
                (org.redisson.api.RMap<String, String>) Mockito.mock(RMap.class);
        Mockito.when(alwaysMap.readAllMap()).thenReturn(Map.of("n1", "akey"));
        doReturn(alwaysMap).when(redisson).getMap(StreamProxyService.ALWAYS_IDX_PREFIX + STREAM);
        when(idx.readAllMap()).thenReturn(Map.of(
                "n1", "akey",
                DST, "k1"));
        svc.cleanupForStream(STREAM);
        // 常驻节点 n1: 不 delStreamProxy 不 closeStreams（观众走光常驻流不断的核心保证）
        verify(zlm, never()).delStreamProxy("n1", "akey");
        verify(zlm, never()).closeStreams("n1", "rtp", STREAM);
        // 非常驻节点 DST: 正常拆除
        verify(zlm).delStreamProxy(DST, "k1");
        verify(idx).delete();
    }

    @Test
    @DisplayName("alwaysOnStreamIds: 扫常驻索引前缀并剥前缀(对账保护用, 评审 P0-2)")
    void alwaysOnStreamIdsScan() {
        org.redisson.api.RKeys keys = Mockito.mock(org.redisson.api.RKeys.class);
        Mockito.when(redisson.getKeys()).thenReturn(keys);
        // 生产侧已迁 KeysScanOptions 形式, stub 同步(宽匹配 options 实例即可)
        Mockito.when(keys.getKeys(Mockito.any(org.redisson.api.options.KeysScanOptions.class)))
                .thenReturn(List.of(StreamProxyService.ALWAYS_IDX_PREFIX + "gb_play_a",
                        StreamProxyService.ALWAYS_IDX_PREFIX + "gb_play_b"));
        var ids = svc.alwaysOnStreamIds();
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of("gb_play_a", "gb_play_b"), ids);
    }
}
