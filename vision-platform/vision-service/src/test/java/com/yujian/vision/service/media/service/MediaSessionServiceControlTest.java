package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmMediaInfo;
import com.yujian.vision.media.zlm.ZlmOpenRtpServerRequest;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.config.MediaProperties;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

/**
 * B2 必改项会话行为测试（docs/07 §3 / docs/06 §2）:
 * #1 暂停豁免 + 续播重开（hook 只记死标不判死 / RESUME·SEEK 重开收流 re-INVITE / 活体恢复走 INFO）;
 * #2 SEEK re-INVITE 200 OK 的 ACK CSeq 回显（RFC 3261 §13.2.2.4）;
 * #3 re-INVITE 4xx(491) 保留健康会话。
 * 纯 Mockito, 不起 Spring 上下文/Redis/数据库; 控制直达 playbackControl, 不经 play() 通道锁。
 */
@ExtendWith(MockitoExtension.class)
class MediaSessionServiceControlTest {

        @Mock
        private GbMediaSessionMapper sessionMapper;
        @Mock
        private GbMediaServerMapper serverMapper;
        @Mock
        private GbDeviceMapper deviceMapper;
        @Mock
        private GbDeviceChannelMapper channelMapper;
        @Mock
        private SsrcPoolService ssrcPool;
        @Mock
        private ZlmApiClient zlm;
        @Mock
        private InviteService invite;
        @Mock
        private PlayUrlService urlService;
        @Mock
        private StreamEventPublisher publisher;
        @Mock
        private SessionCleanupService cleanup;
        @Mock
        private SipTransportProvider transport;
        @Mock
        private GbPlatformProperties platform;
        @Mock
        private RedissonClient redisson;
        /** M4-B3: SEEK 豁免标记桶（hook 注销/none_reader 路径对所有会话统一查询, 缺省无标记=false） */
        @Mock
        private org.redisson.api.RBucket<String> seekBucket;

        private MediaSessionService svc;
        private final MediaProperties media = new MediaProperties();

        private GbMediaSessionEntity s;
        private GbDeviceEntity dev;

        @BeforeAll
        static void initMpMetadata() {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                                GbMediaSessionEntity.class);
        }

        @BeforeEach
        void setUp() {
                svc = new MediaSessionService(sessionMapper, serverMapper, deviceMapper, channelMapper,
                                ssrcPool, zlm, invite, urlService, publisher, cleanup, transport, platform, media,
                                redisson,
                                new com.yujian.vision.service.monitor.VisionMetrics(
                                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                                new MediaNodeScheduler(serverMapper, MediaSessionServiceInviteRetryTest.noopMetrics(),
                                                media),
                                MediaSessionServiceInviteRetryTest.haGate(),
                                org.mockito.Mockito.mock(StreamProxyService.class), null);
                s = new GbMediaSessionEntity();
                s.setId(10L);
                s.setSessionType(GbMediaSessionEntity.TYPE_PLAYBACK);
                s.setStreamId("gb_playback_0102000001_101010_01");
                s.setChannelId("0102000001");
                s.setDeviceDbId(3L);
                s.setChannelDbId(7L);
                s.setServerId("vision-zlm");
                s.setSsrc("1100000001");
                s.setTcpMode(0);
                s.setCallId("playback-call-1");
                s.setStatus("STREAMING");
                s.setViewerCount(1);
                s.setPlaybackStart(LocalDateTime.now().minusSeconds(60));
                s.setPlaybackEnd(LocalDateTime.now().plusSeconds(60));
                dev = new GbDeviceEntity();
                dev.setId(3L);
                dev.setIp("10.0.0.2");
                dev.setPort(5060);
                // 会话列表仅控制/清理类用例消费; INFO 应答等纯回调用例不触发, lenient 防误报
                lenient().when(sessionMapper.selectList(any())).thenReturn(List.of(s));
                // 设备查找仅 SIP 分支用; regist 直达/hook 兜底等用例不触发, lenient 防误报
                lenient().when(deviceMapper.selectById(3L)).thenReturn(dev);
                // M4-B3: isSeeking 查询缺省无标记(GB 用例), doReturn 避开 getBucket 泛型推断
                lenient().doReturn(seekBucket).when(redisson).getBucket(org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("#1 PAUSE → 死亡 hook 豁免(不判死不 BYE) → RESUME 重开收流 + re-INVITE(t=暂停点)")
        void pauseThenDeathHookExemptThenResumeReopens() {
                // PAUSE: 置暂停态 + INFO PAUSE
                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);
                verify(invite, times(1)).sendInfo(anyString(), eq("playback-call-1"),
                                any(GbDeviceEntity.class), anyString(), anyInt(), anyString());

                // 死亡信号(暂停中 ZLM 收流侧按 gap 超时自关 → unregist): 豁免判死, 只记收流已死
                svc.onStreamChanged(s.getStreamId(), false, "");
                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());

                // RESUME: 重开收流端口(SSRC 复用, UDP) + 行条件更新 + re-INVITE(t=暂停点)
                GbMediaServerEntity node = new GbMediaServerEntity();
                node.setServerId("vision-zlm");
                org.mockito.Mockito.lenient().when(serverMapper.selectList(
                                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yujian.vision.service.media.entity.GbMediaServerEntity>>any()))
                                .thenReturn(java.util.List.of(node));
                // reopenAndContinue(暂停续播/拖动重开)按 serverId 直查节点, 与调度器 selectList 并存
                when(serverMapper.selectOne(any())).thenReturn(node);
                when(zlm.openRtpServer(eq("vision-zlm"), any())).thenReturn(30088);
                when(sessionMapper.update(isNull(), any())).thenReturn(1);
                when(sessionMapper.selectById(10L)).thenReturn(s);

                svc.playbackControl(s.getStreamId(), "RESUME", null, null);

                ArgumentCaptor<ZlmOpenRtpServerRequest> openCap = ArgumentCaptor
                                .forClass(ZlmOpenRtpServerRequest.class);
                verify(zlm, times(1)).openRtpServer(eq("vision-zlm"), openCap.capture());
                assertEquals(s.getStreamId(), openCap.getValue().streamId());
                assertFalse(openCap.getValue().enableTcp());
                ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
                verify(invite, times(1)).sendReInvite(any(GbMediaSessionEntity.class), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), fromCap.capture());
                // 续播点 = 暂停点(位置钟 PAUSE 即冻结在窗口起点; 建钟到冻结间毫秒级推进, 容差 5s)
                LocalDateTime from = fromCap.getValue();
                assertFalse(from.isBefore(s.getPlaybackStart()));
                assertFalse(from.isAfter(s.getPlaybackStart().plusSeconds(5)));
                // 重开路径不再发 INFO RESUME
                verify(invite, times(1)).sendInfo(anyString(), anyString(),
                                any(GbDeviceEntity.class), anyString(), anyInt(), anyString());
                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());

                // reopen 的 re-INVITE 200 OK → 补 ACK(reopenAndContinue 内部已登记 callIndex): CSeq 回显
                // + tag 记忆
                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INVITE")
                                .statusCode(200).cseq(99L).callId("playback-call-1").toTag("resume-dev-tag").build());
                ArgumentCaptor<Long> resumeCseqCap = ArgumentCaptor.forClass(Long.class);
                verify(invite, times(1)).sendAck(eq("0102000001"), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), resumeCseqCap.capture());
                assertEquals(99L, resumeCseqCap.getValue());
                verify(invite, times(1)).rememberPeerTag("playback-call-1", "resume-dev-tag");
                verify(invite, times(1)).markAnswered("playback-call-1");
        }

        @Test
        @DisplayName("#1 死亡 hook 只豁免暂停态: on_rtp_server_timeout 同样豁免")
        void rtpServerTimeoutHookAlsoExemptedWhenPaused() {
                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);

                svc.onRtpServerTimeout(s.getStreamId(), "1100000001");

                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());
        }

        @Test
        @DisplayName("#1 暂停未超阈值 → RESUME 直接 INFO 续播(不重开); 恢复后死亡 hook 照常判死")
        void resumeWithinTimeoutIsPlainInfoAndExemptionEnds() {
                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);
                svc.playbackControl(s.getStreamId(), "RESUME", null, null);

                // 活体: INFO PAUSE + INFO RESUME 各一次, 不重开收流
                verify(invite, times(2)).sendInfo(anyString(), anyString(),
                                any(GbDeviceEntity.class), anyString(), anyInt(), anyString());
                verify(zlm, never()).openRtpServer(anyString(), any());
                verify(invite, never()).sendReInvite(any(), anyString(), any(), anyString(), anyInt(), any());

                // 暂停已解除: 后续死亡 hook 恢复判死语义
                when(cleanup.close(10L, "CLOSED", "stream_lost", "设备断流")).thenReturn(true);
                svc.onStreamChanged(s.getStreamId(), false, "");
                verify(cleanup, times(1)).close(10L, "CLOSED", "stream_lost", "设备断流");
                verify(publisher, times(1)).streamClosed(s.getStreamId(), "stream_lost");
        }

        @Test
        @DisplayName("#1 暂停中(收流已死) SEEK → 重开收流 + re-INVITE(t=seekTime)")
        void seekWhilePausedDeadReopensFromSeekTime() {
                GbMediaServerEntity node = new GbMediaServerEntity();
                node.setServerId("vision-zlm");
                org.mockito.Mockito.lenient().when(serverMapper.selectList(
                                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yujian.vision.service.media.entity.GbMediaServerEntity>>any()))
                                .thenReturn(java.util.List.of(node));
                // reopenAndContinue(暂停续播/拖动重开)按 serverId 直查节点, 与调度器 selectList 并存(lenient:
                // 本用例不触发)
                org.mockito.Mockito.lenient().when(serverMapper.selectOne(any())).thenReturn(node);
                when(zlm.openRtpServer(eq("vision-zlm"), any())).thenReturn(30090);
                when(sessionMapper.update(isNull(), any())).thenReturn(1);
                when(sessionMapper.selectById(10L)).thenReturn(s);

                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);
                svc.onStreamChanged(s.getStreamId(), false, ""); // 死亡信号
                LocalDateTime seek = s.getPlaybackStart().plusSeconds(30);
                svc.playbackControl(s.getStreamId(), "SEEK", null, seek);

                ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
                verify(invite, times(1)).sendReInvite(any(GbMediaSessionEntity.class), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), fromCap.capture());
                assertEquals(seek, fromCap.getValue());
                verify(zlm, times(1)).openRtpServer(eq("vision-zlm"), any());
        }

        @Test
        @DisplayName("#1 健康会话 SEEK(未暂停) → 直接 re-INVITE, 不重开收流; 暂停中(<15s) SEEK 同")
        void seekOnHealthyOrAlivePausedSessionJustReInvites() {
                // 未暂停: 直接 re-INVITE
                LocalDateTime seek = s.getPlaybackStart().plusSeconds(30);
                svc.playbackControl(s.getStreamId(), "SEEK", null, seek);
                verify(invite, times(1)).sendReInvite(any(GbMediaSessionEntity.class), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), eq(seek));
                verify(zlm, never()).openRtpServer(anyString(), any());

                // 暂停但未超阈值(收流仍活): SEEK 也只是 re-INVITE + 解除暂停态
                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);
                LocalDateTime seek2 = s.getPlaybackStart().plusSeconds(40);
                svc.playbackControl(s.getStreamId(), "SEEK", null, seek2);
                verify(invite, times(2)).sendReInvite(any(GbMediaSessionEntity.class), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), any(LocalDateTime.class));
                verify(zlm, never()).openRtpServer(anyString(), any());
        }

        @Test
        @DisplayName("#2 SEEK re-INVITE 200 OK → ACK CSeq 回显 200 OK 携带的 INVITE CSeq(RFC 3261 §13.2.2.4)")
        void ackEchoesInviteCseqOn200() {
                when(sessionMapper.selectById(10L)).thenReturn(s);
                svc.playbackControl(s.getStreamId(), "SEEK", null, s.getPlaybackStart().plusSeconds(10));

                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INVITE")
                                .statusCode(200).cseq(42L).callId("playback-call-1").toTag("device-tag").build());

                verify(invite, times(1)).rememberPeerTag("playback-call-1", "device-tag");
                ArgumentCaptor<Long> cseqCap = ArgumentCaptor.forClass(Long.class);
                verify(invite, times(1)).sendAck(eq("0102000001"), eq("playback-call-1"),
                                any(GbDeviceEntity.class), eq("10.0.0.2"), eq(5060), cseqCap.capture());
                assertEquals(42L, cseqCap.getValue());
                // 会话已 STREAMING(re-INVITE 场景): ACK 后索引即撤
                verify(invite, times(1)).markAnswered("playback-call-1");
        }

        @Test
        @DisplayName("#3 re-INVITE 4xx(491 双击 SEEK) → 保留健康会话继续播, 不清理不 BYE")
        void keepHealthySessionOnReInvite491() {
                when(sessionMapper.selectById(10L)).thenReturn(s);
                svc.playbackControl(s.getStreamId(), "SEEK", null, s.getPlaybackStart().plusSeconds(10));

                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INVITE")
                                .statusCode(491).callId("playback-call-1").build());

                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());
                verify(invite, never()).sendBye(anyString(), anyString(), any(), anyString(), anyInt());
                verify(invite, never()).sendAck(anyString(), anyString(), any(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("B5 流注册(regist): hook 带 videoCodec 直通下发, 不查 getMediaList")
        void streamReadyPublishesHookCodecDirectly() {
                s.setStatus("INVITING");

                svc.onStreamChanged(s.getStreamId(), true, "H264");

                verify(publisher, times(1)).streamReady(eq(s.getStreamId()), eq("0102000001"), any(), eq("H264"));
                verify(zlm, never()).listMedia(anyString());
        }

        @Test
        @DisplayName("B5 流注册(regist): hook 无 tracks → 先发未知, 异步重试解析后迟到补投; 同编码去重")
        void streamReadyUnknownThenAsyncRetryRepublishes() {
                s.setStatus("INVITING");
                List<Runnable> tasks = new ArrayList<>();
                svc.setCodecRetryExecutor(tasks::add);
                when(zlm.listMedia("vision-zlm")).thenReturn(List.of(
                                new ZlmMediaInfo("rtp", s.getStreamId(), 1, 100L, 1000L, "H265")));

                svc.onStreamChanged(s.getStreamId(), true, "");

                // 立即下发"未知", 重试任务入队(串行 hook 池零阻塞)
                verify(publisher, times(1)).streamReady(eq(s.getStreamId()), eq("0102000001"), any(), eq(""));
                assertEquals(1, tasks.size());
                // 会话转 STREAMING(等价 updateById 落库后 hook 侧重查), 执行重试 → 补投
                s.setStatus("STREAMING");
                tasks.get(0).run();
                verify(publisher, times(1)).streamReady(eq(s.getStreamId()), eq("0102000001"), any(), eq("H265"));

                // 后续 hook 再带同编码 → 去重不重发
                svc.onStreamChanged(s.getStreamId(), true, "H265");
                verify(publisher, times(2)).streamReady(anyString(), anyString(), any(), anyString());
        }

        /** 以备台身份重建服务(其余依赖复用本类 mock): HA 门禁语义验证（M3-B6 阶段2） */
        private MediaSessionService standbySvc() {
                return new MediaSessionService(sessionMapper, serverMapper, deviceMapper, channelMapper,
                                ssrcPool, zlm, invite, urlService, publisher, cleanup, transport, platform, media,
                                redisson,
                                new com.yujian.vision.service.monitor.VisionMetrics(
                                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                                new MediaNodeScheduler(serverMapper, MediaSessionServiceInviteRetryTest.noopMetrics(),
                                                media),
                                MediaSessionServiceInviteRetryTest.haStandby(),
                                org.mockito.Mockito.mock(StreamProxyService.class), null);
        }

        @Test
        @DisplayName("HA 门禁(M3-B6 阶段2): 备台跳过收流超时判死(hooks 落备台, 暂停态在主台)")
        void standbySkipsRtpServerTimeoutFatal() {
                MediaSessionService standby = standbySvc();

                standby.onRtpServerTimeout(s.getStreamId(), "1100000001");

                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());
        }

        @Test
        @DisplayName("HA 门禁(M3-B6 阶段2): 备台跳过流注销判死; 注册路径不受限照发 STREAM_READY")
        void standbySkipsUnregistFatalButRegistStillWorks() {
                MediaSessionService standby = standbySvc();

                standby.onStreamChanged(s.getStreamId(), false, "");
                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(anyString(), anyString());

                s.setStatus("INVITING");
                standby.onStreamChanged(s.getStreamId(), true, "H264");
                verify(publisher, times(1)).streamReady(eq(s.getStreamId()), eq("0102000001"), any(), eq("H264"));
        }

        @Test
        @DisplayName("#M5-B2 回放控制遇对话终结: 收尾会话(dialog_terminated)+推流关闭事件, 不再连环报错")
        void dialogDeadOnInfoClosesSession() {
                // 模拟 jain-sip 发送侧对话状态检查失败(对端静默终结, 真机 LiveGBS 级联回放实证)
                org.mockito.Mockito.doThrow(new com.yujian.vision.common.exception.BizException(
                                com.yujian.vision.common.api.ErrorCode.SYSTEM_ERROR,
                                "SIP 发送失败: INFO, Dialog x:y:z not yet established or terminated Terminated Dialog"))
                                .when(invite)
                                .sendInfo(any(), any(), any(GbDeviceEntity.class), any(), anyInt(), any());
                // close 返回 true = 本次真翻态, 调用方才推 streamClosed 事件
                when(cleanup.close(eq(10L), eq("FAILED"), eq("dialog_terminated"), anyString()))
                                .thenReturn(true);

                com.yujian.vision.common.exception.BizException e =
                                org.junit.jupiter.api.Assertions.assertThrows(
                                                com.yujian.vision.common.exception.BizException.class,
                                                () -> svc.playbackControl(s.getStreamId(), "PAUSE", null, null));
                assertEquals(com.yujian.vision.common.api.ErrorCode.SESSION_NOT_FOUND, e.getErrorCode());
                org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("回放会话已结束"));
                verify(cleanup).close(eq(10L), eq("FAILED"), eq("dialog_terminated"),
                                anyString());
                verify(publisher).streamClosed(eq("gb_playback_0102000001_101010_01"),
                                eq("dialog_terminated"));
        }

        @Test
        @DisplayName("#M5-B2 非对话终结异常原样上抛: 不收尾不重复清理")
        void nonDialogErrorRethrownUntouched() {
                org.mockito.Mockito.doThrow(new com.yujian.vision.common.exception.BizException(
                                com.yujian.vision.common.api.ErrorCode.SYSTEM_ERROR, "网络不可达"))
                                .when(invite)
                                .sendInfo(any(), any(), any(GbDeviceEntity.class), any(), anyInt(), any());

                com.yujian.vision.common.exception.BizException e =
                                org.junit.jupiter.api.Assertions.assertThrows(
                                                com.yujian.vision.common.exception.BizException.class,
                                                () -> svc.playbackControl(s.getStreamId(), "PAUSE", null, null));
                assertEquals(com.yujian.vision.common.api.ErrorCode.SYSTEM_ERROR, e.getErrorCode());
                verify(cleanup, never()).close(anyLong(), anyString(), anyString(), anyString());
                verify(publisher, never()).streamClosed(any(), any());
        }

        @Test
        @DisplayName("#M5-B2 续播重开前先关旧 rtpServer(防 -300 already exists), 再开新端口")
        void reopenClosesStaleRtpServerFirst() {
                GbMediaServerEntity node = new GbMediaServerEntity();
                node.setServerId("vision-zlm");
                org.mockito.Mockito.lenient().when(serverMapper.selectOne(any())).thenReturn(node);
                when(zlm.openRtpServer(eq("vision-zlm"), any())).thenReturn(30091);
                when(sessionMapper.update(isNull(), any())).thenReturn(1);
                when(sessionMapper.selectById(10L)).thenReturn(s);

                svc.playbackControl(s.getStreamId(), "PAUSE", null, null);
                svc.onStreamChanged(s.getStreamId(), false, ""); // 死亡信号
                svc.playbackControl(s.getStreamId(), "SEEK", null, s.getPlaybackStart().plusSeconds(10));

                org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(zlm);
                inOrder.verify(zlm).closeRtpServer(eq("vision-zlm"),
                                eq("gb_playback_0102000001_101010_01"));
                inOrder.verify(zlm).openRtpServer(eq("vision-zlm"), any());
        }

        @Test
        @DisplayName("#M5-B2 回放控制 INFO 被设备拒绝(400) → 推 PLAYBACK_CONTROL_FAILED 事件")
        void infoRejectedPushesControlFailed() {
                when(sessionMapper.selectOne(any())).thenReturn(s);

                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INFO")
                                .statusCode(400).callId("playback-call-1").build());

                verify(publisher).playbackControlFailed("gb_playback_0102000001_101010_01", 400);
        }

        @Test
        @DisplayName("#M5-B2 INFO 2xx 受理静默通过; 会话查不到(已清理)不推事件")
        void infoAcceptedOrUnknownSilent() {
                when(sessionMapper.selectOne(any())).thenReturn(s);
                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INFO")
                                .statusCode(200).callId("playback-call-1").build());
                verify(publisher, never()).playbackControlFailed(any(), anyInt());

                when(sessionMapper.selectOne(any())).thenReturn(null);
                svc.onSipResponse(SipEvent.builder().type(SipEvent.Type.RESPONSE).method("INFO")
                                .statusCode(400).callId("gone").build());
                verify(publisher, never()).playbackControlFailed(any(), anyInt());
        }
}
