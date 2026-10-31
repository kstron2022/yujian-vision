package com.yujian.vision.service.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.media.zlm.ZlmSnap;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.ha.HaPeerClient;
import com.yujian.vision.service.media.config.SnapshotProperties;
import com.yujian.vision.service.media.entity.GbChannelSnapshotEntity;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.entity.SnapshotVo;
import com.yujian.vision.service.media.mapper.GbChannelSnapshotMapper;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * M3-B7 阶段2 服务端抓图单测: 纯 Mockito + @TempDir, 不起 Spring 上下文。
 * 真图用 ImageIO 现做（BufferedImage 320x240 → JPEG, 前 3 字节天然 FF D8 FF）,
 * 占位图按 OkHttp 实现契约回 PNG。自动抓图经 autoDelaySeconds=0 使调度即时执行,
 * 断言用 Mockito timeout/after 轮询, 不写线程 sleep 长等待。
 */
class SnapshotServiceTest {

    private static final long CH_ID = 1L;
    private static final String CHANNEL_ID = "34020000001310000001";
    private static final String NODE = "vision-zlm";
    private static final String STREAM = "gb_play_" + CHANNEL_ID;

    @TempDir
    Path tmp;

    private SnapshotProperties props;
    private GbChannelSnapshotMapper snapshotMapper;
    private GbDeviceChannelMapper channelMapper;
    private MediaSessionService sessions;
    private GbMediaServerMapper serverMapper;
    private ZlmApiClient zlm;
    private PlayUrlService urls;
    private RedissonClient redisson;
    private RBucket<String> bucket;
    private HaPeerClient haPeer;
    private com.yujian.vision.service.media.service.StreamProxyService streamProxies;
    private SnapshotService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new SnapshotProperties();
        props.setDir(tmp.toString());
        props.setPollIntervalMs(50);
        props.setTimeoutMs(2000);
        snapshotMapper = mock(GbChannelSnapshotMapper.class);
        channelMapper = mock(GbDeviceChannelMapper.class);
        sessions = mock(MediaSessionService.class);
        serverMapper = mock(GbMediaServerMapper.class);
        zlm = mock(ZlmApiClient.class);
        urls = mock(PlayUrlService.class);
        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        haPeer = mock(HaPeerClient.class);
        streamProxies = mock(com.yujian.vision.service.media.service.StreamProxyService.class);
        // 兜底契约: alwaysOnEntries 无登记返回空 map; 用例需要时再覆盖
        lenient().when(streamProxies.alwaysOnEntries(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Map.of());
        lenient().when(redisson.<String>getBucket(anyString())).thenReturn(bucket);
        lenient().when(bucket.setIfAbsent(anyString(), any())).thenReturn(true);
        lenient().when(haPeer.isLocal()).thenReturn(true);
        lenient().when(channelMapper.selectOne(any())).thenReturn(channel());
        lenient().when(serverMapper.selectOne(any())).thenReturn(node());
        lenient().when(urls.buildSelfPullUrl(any(), anyString(), anyLong())).thenReturn("http://pull/flv");
        service = new SnapshotService(props, snapshotMapper, channelMapper, sessions, serverMapper,
                zlm, urls, redisson, haPeer,
                org.mockito.Mockito.mock(com.yujian.vision.service.device.mapper.GbDeviceMapper.class),
                org.mockito.Mockito.mock(com.yujian.vision.service.device.spi.DeviceAccessRegistry.class),
                streamProxies,
                "test-aes-key");
    }

    private static GbDeviceChannelEntity channel() {
        GbDeviceChannelEntity c = new GbDeviceChannelEntity();
        c.setId(CH_ID);
        c.setChannelId(CHANNEL_ID);
        return c;
    }

    private static GbMediaServerEntity node() {
        GbMediaServerEntity n = new GbMediaServerEntity();
        n.setServerId(NODE);
        n.setHostIp("10.0.0.3");
        n.setHttpPort(80);
        return n;
    }

    private static GbMediaSessionEntity streaming() {
        GbMediaSessionEntity s = new GbMediaSessionEntity();
        s.setId(9L);
        s.setChannelDbId(CH_ID);
        s.setStreamId(STREAM);
        s.setStatus("STREAMING");
        s.setServerId(NODE);
        return s;
    }

    /** ImageIO 现做真 JPEG（320x240）: content-type 与魔数双条件均真 */
    private static byte[] realJpeg() {
        try {
            BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ZlmSnap placeholder() {
        return new ZlmSnap("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A});
    }

    private static ZlmSnap jpegSnap() {
        return new ZlmSnap("image/jpeg", realJpeg());
    }

    @Test
    @DisplayName("手动抓图: 复用活跃流, 占位图轮询至真图, 落盘原图+缩略图(320x240→160x120)并 insert 行")
    void manualCaptureReusesActiveStreamAndPersists() {
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt()))
                .thenReturn(placeholder(), jpegSnap());
        when(snapshotMapper.selectById(CH_ID)).thenReturn(null);

        SnapshotVo vo = service.captureManual(CH_ID);

        verify(sessions, never()).stop(anyString(), any());
        ArgumentCaptor<GbChannelSnapshotEntity> captor = ArgumentCaptor.forClass(GbChannelSnapshotEntity.class);
        verify(snapshotMapper).insert(captor.capture());
        GbChannelSnapshotEntity row = captor.getValue();
        assertThat(row.getChannelDbId()).isEqualTo(CH_ID);
        assertThat(row.getSource()).isEqualTo(GbChannelSnapshotEntity.SOURCE_MANUAL);
        assertThat(row.getServerId()).isEqualTo(NODE);
        assertThat(row.getWidth()).isEqualTo(320);
        assertThat(row.getHeight()).isEqualTo(240);
        assertThat(row.getSizeBytes()).isPositive();
        assertThat(row.getFileName()).endsWith(".jpg");
        assertThat(row.getThumbName()).endsWith("_t.jpg");
        assertThat(Files.exists(tmp.resolve(row.getFileName()))).isTrue();
        assertThat(Files.exists(tmp.resolve(row.getThumbName()))).isTrue();
        try {
            BufferedImage thumb = ImageIO.read(tmp.resolve(row.getThumbName()).toFile());
            assertThat(thumb.getWidth()).isEqualTo(160);
            assertThat(thumb.getHeight()).isEqualTo(120);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(vo.url()).startsWith("/api/channels/" + CH_ID + "/snapshot?size=full");
        assertThat(vo.thumbUrl()).contains("size=thumb");
        assertThat(vo.time()).isNotNull();
        assertThat(vo.source()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("抓帧时序: 单次 getSnap 超时跟随剩余预算(≥10s), 不得用固定小值掐断"
            + "——242 实测 H265 冷抓首帧需 4~5.4s, 提前掐断会让 ZLM snap 播放器卡死在占位图态")
    void snapCallTimeoutFollowsRemainingBudget() {
        props.setTimeoutMs(15000);
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());

        service.captureManual(CH_ID);

        ArgumentCaptor<Integer> sec = ArgumentCaptor.forClass(Integer.class);
        verify(zlm).getSnap(eq(NODE), anyString(), sec.capture(), anyInt());
        assertThat(sec.getValue()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("抓帧超时: 一直占位图 → 预算耗尽 1013")
    void placeholderUntilBudgetExhaustedThrows1013() {
        props.setTimeoutMs(400);
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(placeholder());

        BizException e = catchThrowableOfType(BizException.class, () -> service.captureManual(CH_ID));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_FAILED);
        verify(snapshotMapper, never()).insert(any(GbChannelSnapshotEntity.class));
    }

    @Test
    @DisplayName("通道不存在 → 404")
    void missingChannelThrowsNotFound() {
        when(channelMapper.selectOne(any())).thenReturn(null);
        BizException e = catchThrowableOfType(BizException.class, () -> service.captureManual(99L));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("空闲抓图: 本机活跃 → playOnDemand 临时建流, 等到 STREAMING 后抓, 抓完停流")
    void idleCaptureBuildsLocallyThenStops() {
        props.setIdleEnabled(true);
        when(sessions.findActiveLive(CH_ID)).thenReturn(null, streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());

        SnapshotVo vo = service.captureManual(CH_ID);

        verify(sessions).playOnDemand(STREAM, null, 1);
        verify(haPeer, never()).requestOnDemand(anyString(), anyString(), anyInt());
        verify(sessions).stop(STREAM, null);
        assertThat(vo.source()).isEqualTo(GbChannelSnapshotEntity.SOURCE_IDLE);
    }

    @Test
    @DisplayName("空闲抓图停流容错: stop 抛 SESSION_NOT_FOUND(已关闭)不影响抓图成功")
    void idleStopSwallowsSessionNotFound() {
        props.setIdleEnabled(true);
        when(sessions.findActiveLive(CH_ID)).thenReturn(null, streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已关闭"))
                .when(sessions).stop(STREAM, null);

        SnapshotVo vo = service.captureManual(CH_ID);

        assertThat(vo.source()).isEqualTo(GbChannelSnapshotEntity.SOURCE_IDLE);
    }

    @Test
    @DisplayName("空闲抓图: 备台身份 → 委托活跃实例建流, 本进程不建")
    void idleCaptureDelegatesToPeerOnStandby() {
        props.setIdleEnabled(true);
        when(haPeer.isLocal()).thenReturn(false);
        when(haPeer.requestOnDemand(STREAM, null, 1)).thenReturn(true);
        when(sessions.findActiveLive(CH_ID)).thenReturn(null, streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());

        service.captureManual(CH_ID);

        verify(haPeer).requestOnDemand(STREAM, null, 1);
        verify(sessions, never()).playOnDemand(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("空闲抓图: 备台委托失败 → 1013")
    void idleCaptureDelegationFailureThrows1013() {
        props.setIdleEnabled(true);
        when(haPeer.isLocal()).thenReturn(false);
        when(haPeer.requestOnDemand(STREAM, null, 1)).thenReturn(false);

        BizException e = catchThrowableOfType(BizException.class, () -> service.captureManual(CH_ID));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_FAILED);
        verify(snapshotMapper, never()).insert(any(GbChannelSnapshotEntity.class));
    }

    @Test
    @DisplayName("空闲抓图建流超时: 会话一直不到 STREAMING → 1013, 且回收在途流(stop, 评审 P2-1)")
    void idleBuildTimeoutThrows1013() {
        props.setIdleEnabled(true);
        service.idleBuildWaitMs = 300;
        when(sessions.findActiveLive(CH_ID)).thenReturn(null);

        BizException e = catchThrowableOfType(BizException.class, () -> service.captureManual(CH_ID));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_FAILED);
        assertThat(e.getMessage()).contains("建流超时");
        // 建流请求已发出(playOnDemand viewer+1): 超时也必须回收, 否则自建流白耗带宽/
        // 复用他人会话则计数永久多 1
        verify(sessions).stop(STREAM, null);
    }

    @Test
    @DisplayName("M5-B1 常驻流快照兜底: 无会话但常驻索引命中 → 直接热抓, 不建临时会话不停流")
    void alwaysOnStreamSnapshotFallback() throws Exception {
        // 无活跃会话 + 未开空闲抓图: 原逻辑会抛 1014, 常驻兜底后应走热抓
        when(sessions.findActiveLive(CH_ID)).thenReturn(null);
        when(streamProxies.alwaysOnEntries(STREAM))
                .thenReturn(java.util.Map.of(NODE, "__defaultVhost__/rtp/" + STREAM));
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());

        service.captureManual(CH_ID);

        // 热抓路径: 不建临时会话(不 playOnDemand)不回收(不 stop), 抓帧落在常驻节点上
        verify(sessions, never()).playOnDemand(anyString(), any(), anyInt());
        verify(sessions, never()).stop(anyString(), any());
        verify(zlm).getSnap(eq(NODE), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("再抓图缩略失败(评审 P1-1): thumbName 写空串而非 null(MP NOT_NULL 策略防悬空), 旧缩略文件删除, 读侧回退原图")
    void recaptureThumbFailureWritesBlankSentinel() throws Exception {
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        // thumbWidth=0 → writeThumb 直接回 null: 缩略失败的确定性触发路径
        // （垃圾字节不可用——ImageIO.read 对其抛 IOException, 走"解码失败 1013"分支而非缩略失败）
        props.setThumbWidth(0);
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());
        Files.write(tmp.resolve("1_old_t.jpg"), new byte[]{2});
        GbChannelSnapshotEntity old = new GbChannelSnapshotEntity();
        old.setChannelDbId(CH_ID);
        old.setFileName("1_old.jpg");
        old.setThumbName("1_old_t.jpg");
        old.setWidth(1920);
        old.setHeight(1080);
        old.setCaptureTime(LocalDateTime.now().minusDays(1));
        when(snapshotMapper.selectById(CH_ID)).thenReturn(old);

        service.captureManual(CH_ID);

        ArgumentCaptor<GbChannelSnapshotEntity> captor = ArgumentCaptor.forClass(GbChannelSnapshotEntity.class);
        verify(snapshotMapper).updateById(captor.capture());
        GbChannelSnapshotEntity row = captor.getValue();
        assertThat(row.getThumbName()).isEqualTo("");   // null 会被 updateById NOT_NULL 策略跳过 → DB 残留旧名
        assertThat(row.getWidth()).isEqualTo(320);      // 原图解码正常, 尺寸照实落
        assertThat(Files.exists(tmp.resolve("1_old_t.jpg"))).isFalse();
        // 读侧: thumbName 空串 → 回退原图, 不再 1013（修复前的失败形态）
        when(snapshotMapper.selectById(CH_ID)).thenReturn(row);
        SnapshotService.ServedImage img = service.readForServe(CH_ID, true);
        assertThat(img.body()).hasSize((int) java.nio.file.Files.size(tmp.resolve(row.getFileName())));
    }

    @Test
    @DisplayName("同通道并发首次抓图(评审 P2-2): insert PK 冲突转 updateById, 不落 500")
    void insertDuplicateKeyFallsBackToUpdate() {
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());
        when(snapshotMapper.selectById(CH_ID)).thenReturn(null);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("dup"))
                .when(snapshotMapper).insert(any(GbChannelSnapshotEntity.class));

        SnapshotVo vo = service.captureManual(CH_ID);

        verify(snapshotMapper).updateById(any(GbChannelSnapshotEntity.class));
        assertThat(vo.url()).isNotBlank();
    }

    @Test
    @DisplayName("无流且未开空闲抓图 → 1014")
    void noStreamAndIdleDisabledThrows1014() {
        props.setIdleEnabled(false);
        when(sessions.findActiveLive(CH_ID)).thenReturn(null);

        BizException e = catchThrowableOfType(BizException.class, () -> service.captureManual(CH_ID));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_NO_STREAM);
        assertThat(e.getErrorCode().getCode()).isEqualTo(1014);
    }

    @Test
    @DisplayName("自动抓图: 点播流就绪后延迟调度, 落 AUTO 行")
    void autoCaptureOnStreamReadyPersistsAutoRow() {
        props.setAutoDelaySeconds(0);
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());
        when(snapshotMapper.selectById(CH_ID)).thenReturn(null);

        service.onStreamReady(STREAM);

        ArgumentCaptor<GbChannelSnapshotEntity> captor = ArgumentCaptor.forClass(GbChannelSnapshotEntity.class);
        verify(snapshotMapper, timeout(3000)).insert(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(GbChannelSnapshotEntity.SOURCE_AUTO);
    }

    @Test
    @DisplayName("自动抓图守卫: 总开关关 / 非实况前缀 → 不调度")
    void autoCaptureGuardsSkipScheduling() {
        props.setEnabled(false);
        props.setAutoDelaySeconds(0);
        service.onStreamReady(STREAM);
        service.onStreamReady("gb_playback_0102000001_101010_01");
        service.onStreamReady("talk_audio_x");
        service.onStreamReady("gb_play_");
        verify(snapshotMapper, after(500).never()).insert(any(GbChannelSnapshotEntity.class));
        verify(channelMapper, after(500).never()).selectOne(any());
    }

    @Test
    @DisplayName("自动抓图节流: 已有图未超 auto-refresh-seconds → 跳过不抓")
    void autoCaptureThrottledByFreshImage() {
        props.setAutoDelaySeconds(0);
        props.setAutoRefreshSeconds(1800);
        GbChannelSnapshotEntity fresh = new GbChannelSnapshotEntity();
        fresh.setChannelDbId(CH_ID);
        fresh.setFileName("1_old.jpg");
        fresh.setCaptureTime(LocalDateTime.now().minusMinutes(10));
        when(snapshotMapper.selectById(CH_ID)).thenReturn(fresh);

        service.onStreamReady(STREAM);

        verify(snapshotMapper, after(1000).never()).insert(any(GbChannelSnapshotEntity.class));
        verify(zlm, after(1000).never()).getSnap(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("自动抓图失败静默: 无流(1014)只告警不外抛")
    void autoCaptureSwallowsBizFailure() {
        props.setAutoDelaySeconds(0);
        props.setIdleEnabled(false);
        when(sessions.findActiveLive(CH_ID)).thenReturn(null);

        service.onStreamReady(STREAM);

        verify(snapshotMapper, after(1000).never()).insert(any(GbChannelSnapshotEntity.class));
    }

    @Test
    @DisplayName("列表批量填充: 一次 IN 查按 channelDbId 回填, 无图=null")
    void fillSnapshotsMapsRowsByChannelId() {
        GbDeviceChannelEntity c1 = channel();
        GbDeviceChannelEntity c2 = new GbDeviceChannelEntity();
        c2.setId(2L);
        GbChannelSnapshotEntity row = new GbChannelSnapshotEntity();
        row.setChannelDbId(CH_ID);
        row.setFileName("1_1.jpg");
        row.setCaptureTime(LocalDateTime.now());
        row.setSource(GbChannelSnapshotEntity.SOURCE_AUTO);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(row));

        service.fillSnapshots(List.of(c1, c2));

        assertThat(c1.getSnapshot()).isNotNull();
        assertThat(c1.getSnapshot().url()).contains("/api/channels/" + CH_ID + "/snapshot");
        assertThat(c2.getSnapshot()).isNull();
    }

    @Test
    @DisplayName("upsert 写新删旧: 已有行 → updateById 且旧原图/缩略图文件被删")
    void upsertUpdatesRowAndDeletesOldFiles() throws Exception {
        when(sessions.findActiveLive(CH_ID)).thenReturn(streaming());
        when(zlm.getSnap(eq(NODE), anyString(), anyInt(), anyInt())).thenReturn(jpegSnap());
        Files.write(tmp.resolve("1_old.jpg"), new byte[]{1});
        Files.write(tmp.resolve("1_old_t.jpg"), new byte[]{2});
        GbChannelSnapshotEntity old = new GbChannelSnapshotEntity();
        old.setChannelDbId(CH_ID);
        old.setFileName("1_old.jpg");
        old.setThumbName("1_old_t.jpg");
        old.setCaptureTime(LocalDateTime.now().minusDays(1));
        when(snapshotMapper.selectById(CH_ID)).thenReturn(old);

        service.captureManual(CH_ID);

        verify(snapshotMapper, never()).insert(any(GbChannelSnapshotEntity.class));
        verify(snapshotMapper).updateById(any(GbChannelSnapshotEntity.class));
        assertThat(Files.exists(tmp.resolve("1_old.jpg"))).isFalse();
        assertThat(Files.exists(tmp.resolve("1_old_t.jpg"))).isFalse();
    }

    @Test
    @DisplayName("读图: size=thumb 优先缩略, 缩略缺失回退原图, 行缺失 404")
    void readForServePrefersThumbAndFallsBack() throws Exception {
        Files.write(tmp.resolve("1.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2});
        Files.write(tmp.resolve("1_t.jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8, 9});
        GbChannelSnapshotEntity row = new GbChannelSnapshotEntity();
        row.setChannelDbId(CH_ID);
        row.setFileName("1.jpg");
        row.setThumbName("1_t.jpg");
        row.setCaptureTime(LocalDateTime.of(2026, 9, 16, 10, 0));
        when(snapshotMapper.selectById(CH_ID)).thenReturn(row);

        SnapshotService.ServedImage thumb = service.readForServe(CH_ID, true);
        assertThat(thumb.body()).hasSize(3);
        assertThat(thumb.captureTime()).isEqualTo(LocalDateTime.of(2026, 9, 16, 10, 0));

        GbChannelSnapshotEntity noThumb = new GbChannelSnapshotEntity();
        noThumb.setChannelDbId(CH_ID);
        noThumb.setFileName("1.jpg");
        noThumb.setThumbName(null);
        noThumb.setCaptureTime(row.getCaptureTime());
        when(snapshotMapper.selectById(CH_ID)).thenReturn(noThumb);
        SnapshotService.ServedImage fallback = service.readForServe(CH_ID, true);
        assertThat(fallback.body()).hasSize(4);
        assertThat(fallback.body()).containsExactly(0xFF, 0xD8, 1, 2);

        when(snapshotMapper.selectById(CH_ID)).thenReturn(null);
        BizException e = catchThrowableOfType(BizException.class, () -> service.readForServe(CH_ID, false));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("签名 URL: toVo 与 verifyUrl 同格式闭环; 篡改/换档/过期/缺参一律拒绝")
    void signRoundtripAndRejection() {
        GbChannelSnapshotEntity row = new GbChannelSnapshotEntity();
        row.setChannelDbId(CH_ID);
        row.setFileName("1.jpg");
        row.setCaptureTime(LocalDateTime.now());
        row.setSource(GbChannelSnapshotEntity.SOURCE_MANUAL);

        SnapshotVo vo = service.toVo(row);
        String sign = param(vo.url(), "sign");
        String expire = param(vo.url(), "expire");

        assertThat(sign).isNotBlank();
        assertThat(service.verifyUrl(CH_ID, "full", expire, sign)).isTrue();
        assertThat(service.verifyUrl(CH_ID, "thumb", expire, param(vo.thumbUrl(), "sign"))).isTrue();
        assertThat(service.verifyUrl(CH_ID, "full", expire, sign.substring(1) + "0")).isFalse();
        assertThat(service.verifyUrl(CH_ID, "thumb", expire, sign)).isFalse();
        assertThat(service.verifyUrl(CH_ID, "full", "1", sign)).isFalse();
        assertThat(service.verifyUrl(CH_ID, "full", "abc", sign)).isFalse();
        assertThat(service.verifyUrl(CH_ID, "full", "", sign)).isFalse();
        assertThat(service.verifyUrl(CH_ID, null, expire, sign)).isFalse();
    }

    private static String param(String url, String key) {
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }
}
