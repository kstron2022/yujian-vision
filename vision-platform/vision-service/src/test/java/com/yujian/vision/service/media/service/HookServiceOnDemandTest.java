package com.yujian.vision.service.media.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.cascade.service.CascadeInviteService;
import com.yujian.vision.service.ha.HaPeerClient;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.monitor.VisionMetrics;
import com.yujian.vision.service.record.CloudRecordService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * M3-B7 on_stream_not_found 按需点播准入链单测。
 *
 * <p>准入链是"便宜校验 → 异步建流"两段: 前半段（开关/前缀/验签/去重）在 hook 线程同步完成,
 * 故负例可直接同步断言"没动过 Redis/没提交建流"; 后半段在独立池里跑, 正例用 timeout 等待。</p>
 */
class HookServiceOnDemandTest {

    private static final String STREAM = "gb_play_34020000001310000001";
    private static final String NODE = "vision-zlm";
    private static final String SECRET = "vision-zlm-secret";

    private MediaSessionService sessions;
    private PlayUrlService urlService;
    private GbMediaServerMapper serverMapper;
    private RedissonClient redisson;
    private RBucket<String> bucket;
    private HaPeerClient haPeer;
    private MediaProperties media;
    private HookService hook;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessions = mock(MediaSessionService.class);
        urlService = mock(PlayUrlService.class);
        serverMapper = mock(GbMediaServerMapper.class);
        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        haPeer = mock(HaPeerClient.class);
        media = new MediaProperties();
        AesCipher aes = mock(AesCipher.class);
        lenient().when(aes.decrypt("enc")).thenReturn(SECRET);
        GbMediaServerEntity node = new GbMediaServerEntity();
        node.setServerId(NODE);
        node.setStatus(1);
        node.setSignSecret("enc");
        lenient().when(serverMapper.selectOne(any())).thenReturn(node);
        lenient().when(serverMapper.selectList(any())).thenReturn(List.of(node));
        lenient().when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        lenient().when(bucket.setIfAbsent(anyString(), any())).thenReturn(true);
        lenient().when(urlService.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        lenient().when(haPeer.isLocal()).thenReturn(true);

        hook = new HookService(sessions, urlService, serverMapper, mock(ZlmApiClient.class), redisson,
                aes, mock(CloudRecordService.class), mock(VisionMetrics.class),
                mock(CascadeInviteService.class), media, haPeer, mock(SnapshotService.class));
    }

    private static Map<String, Object> params() {
        return Map.of("sign", "s1", "expire", "9999999999");
    }

    /** 去重键在 hook 线程同步写入（建流提交之前）, 负例据此判断"是否走到建流那一步" */
    private void verifyNoBuildSubmitted() {
        verify(redisson, never()).getBucket(anyString());
        verify(sessions, after(300).never()).playOnDemand(anyString(), anyString(), anyInt());
        verify(haPeer, after(300).never()).requestOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("开关关闭: 只记日志, 不验签不去重不建流")
    void disabledSwitchDoesNothing() {
        media.setOnDemandEnabled(false);
        hook.streamNotFound("rtp", STREAM, params(), NODE);
        verifyNoBuildSubmitted();
    }

    @Test
    @DisplayName("窄准入: 非 rtp app / 回放流 / 空后缀一律不处理")
    void rejectsNonLiveStream() {
        hook.streamNotFound("record", STREAM, params(), NODE);
        hook.streamNotFound("rtp", "gb_playback_0102000001_101010_01", params(), NODE);
        hook.streamNotFound("rtp", "gb_play_", params(), NODE);
        hook.streamNotFound("rtp", null, params(), NODE);
        verifyNoBuildSubmitted();
    }

    @Test
    @DisplayName("验签失败(缺 sign/expire 或签名不符): 不建流——建流是耗资源动作, 不放松准入")
    void rejectsInvalidSign() {
        // 缺参数
        hook.streamNotFound("rtp", STREAM, Map.of("sign", "s1"), NODE);
        hook.streamNotFound("rtp", STREAM, null, NODE);
        // 签名不符
        when(urlService.verify(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        hook.streamNotFound("rtp", STREAM, params(), NODE);
        verifyNoBuildSubmitted();
    }

    @Test
    @DisplayName("验签用 hook 报告节点的密钥(B6 F1 同口径)")
    void verifiesWithReportedNodeSecret() {
        hook.streamNotFound("rtp", STREAM, params(), NODE);
        verify(urlService, timeout(2000)).verify(STREAM, "s1", "9999999999", SECRET);
    }

    @Test
    @DisplayName("节点未知: 遍历全部节点密钥兜底验签")
    void unknownNodeFallsBackToAllSecrets() {
        when(serverMapper.selectOne(any())).thenReturn(null);
        hook.streamNotFound("rtp", STREAM, params(), "unknown-node");
        verify(urlService, timeout(2000)).verify(STREAM, "s1", "9999999999", SECRET);
    }

    @Test
    @DisplayName("去重命中(建流在途): 直接返回, 不再建流——ZLM 仍在等待首轮结果")
    void dedupHitSkipsBuild() {
        when(bucket.setIfAbsent(anyString(), any())).thenReturn(false);
        hook.streamNotFound("rtp", STREAM, params(), NODE);
        verify(sessions, never()).playOnDemand(anyString(), anyString(), anyInt());
        verify(haPeer, never()).requestOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("本机活跃: 按 on-demand-profile 配置的档位本地建流, 流 pin 到 hook 请求节点")
    void localInstanceBuildsWithConfiguredProfile() {
        media.setOnDemandProfile(2);
        hook.streamNotFound("rtp", STREAM, params(), NODE);

        verify(redisson).getBucket(HookService.ON_DEMAND_KEY + STREAM);
        verify(bucket).setIfAbsent(eq("1"), any());
        verify(sessions, timeout(2000)).playOnDemand(STREAM, NODE, 2);
        verify(haPeer, never()).requestOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("备台(无 SIP 栈): 委托活跃实例建流, 本进程不建")
    void standbyDelegatesToActivePeer() {
        when(haPeer.isLocal()).thenReturn(false);
        hook.streamNotFound("rtp", STREAM, params(), NODE);

        verify(haPeer, timeout(2000)).requestOnDemand(STREAM, NODE, 1);
        verify(sessions, never()).playOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("建流抛异常: 只告警不抛(播放器按 maxStreamWaitMS 超时重试再触发一轮)")
    void buildFailureIsSwallowed() {
        when(sessions.playOnDemand(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("无在线流媒体节点"));

        hook.streamNotFound("rtp", STREAM, params(), NODE);

        verify(sessions, timeout(2000)).playOnDemand(STREAM, NODE, 1);
    }

    @Test
    @DisplayName("hook 池不被阻塞: 建流未完成时 streamNotFound 已返回")
    void returnsImmediatelyWithoutWaitingForBuild() {
        // playOnDemand 阻塞 1s, 但 hook 线程不得等它: 否则 ZLM 侧 maxStreamWaitMS 窗口被吃掉
        when(sessions.playOnDemand(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(1000L);
            return null;
        });
        long t0 = System.nanoTime();
        hook.streamNotFound("rtp", STREAM, params(), NODE);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        org.junit.jupiter.api.Assertions.assertTrue(ms < 500,
                "hook 线程应快速返回, 实际耗时 " + ms + "ms");
    }
}
