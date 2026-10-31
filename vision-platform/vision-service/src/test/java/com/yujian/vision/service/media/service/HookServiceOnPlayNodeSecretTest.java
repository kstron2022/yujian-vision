package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

import java.util.Map;

/**
 * F1(M3-B6 阶段1): on_play 验签密钥来源于 hook 报告的节点行。
 * 多节点密钥互相独立: 用 A 节点密钥签的地址不能经 B 节点蒙混。
 */
class HookServiceOnPlayNodeSecretTest {

    private static final String SECRET_1 = "vision-zlm-secret";
    private static final String SECRET_2 = "vision-zlm2-secret";

    private GbMediaServerMapper serverMapper;
    private PlayUrlService urlService;
    private HookService hook;

    @BeforeEach
    void setUp() {
        serverMapper = Mockito.mock(GbMediaServerMapper.class);
        urlService = Mockito.mock(PlayUrlService.class);
        AesCipher aes = Mockito.mock(AesCipher.class);
        lenient().when(aes.decrypt("enc-1")).thenReturn(SECRET_1);
        lenient().when(aes.decrypt("enc-2")).thenReturn(SECRET_2);
        hook = new HookService(Mockito.mock(MediaSessionService.class), urlService, serverMapper,
                Mockito.mock(ZlmApiClient.class), Mockito.mock(RedissonClient.class), aes,
                Mockito.mock(CloudRecordService.class), Mockito.mock(VisionMetrics.class),
                Mockito.mock(CascadeInviteService.class), Mockito.mock(MediaProperties.class),
                Mockito.mock(HaPeerClient.class), Mockito.mock(SnapshotService.class));
    }

    private static GbMediaServerEntity node(String encSecret) {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setStatus(1);
        n.setSignSecret(encSecret);
        return n;
    }

    @Test
    @DisplayName("hook 报告节点2: 验签用节点2密钥(而非首个在线节点)")
    void hookReportedNodeSecretUsed() {
        when(serverMapper.selectOne(any())).thenReturn(node("enc-2"));
        when(urlService.verify(anyString(), anyString(), anyString(), eq(SECRET_2))).thenReturn(true);
        when(urlService.verify(anyString(), anyString(), anyString(), eq(SECRET_1))).thenReturn(false);
        assertTrue(hook.onPlay("rtp", "s1", Map.of("sign", "ok", "expire", "9999999999"), "vision-zlm2"));
    }

    @Test
    @DisplayName("hook 报告节点1: 验签用节点1密钥")
    void hookReportedNode1SecretUsed() {
        when(serverMapper.selectOne(any())).thenReturn(node("enc-1"));
        when(urlService.verify(anyString(), anyString(), anyString(), eq(SECRET_1))).thenReturn(true);
        when(urlService.verify(anyString(), anyString(), anyString(), eq(SECRET_2))).thenReturn(false);
        assertTrue(hook.onPlay("rtp", "s1", Map.of("sign", "ok", "expire", "9999999999"), "vision-zlm"));
    }

    @Test
    @DisplayName("hook 未带 serverId: 按在线节点兜底(历史口径)")
    void missingServerIdFallsBackToOnlineNode() {
        when(serverMapper.selectOne(any())).thenReturn(node("enc-1"));
        when(urlService.verify(anyString(), anyString(), anyString(), eq(SECRET_1))).thenReturn(true);
        assertTrue(hook.onPlay("rtp", "s1", Map.of("sign", "ok", "expire", "9999999999"), ""));
    }

    @Test
    @DisplayName("查无节点: 拒绝")
    void noNodeRejects() {
        when(serverMapper.selectOne(any())).thenReturn(null);
        assertFalse(hook.onPlay("rtp", "s1", Map.of("sign", "ok", "expire", "9999999999"), "vision-zlm2"));
    }
}
