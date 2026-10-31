package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.sip.transport.SipEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 语音对讲会话编排（M3-B5, GB/T 28181-2022 Talk 流程, docs/03 §5.x）。
 *
 * <p>与点播同构、方向相反：点播是设备→平台推视频；对讲是平台→设备推音频。三条腿：</p>
 * <ul>
 *   <li>信令腿: INVITE s=Talk + m=audio（offer 端口=设备腿 openRtpServer 端口）→ 设备 200 OK
 *       应答 SDP 给出设备收流地址（c=/m=audio/y=）→ ACK → begin 时向该地址推音频 → BYE</li>
 *   <li>设备腿: openRtpServer(app=rtp, streamId=gb_talk_*) 绑定 offer 端口（sendrecv 兼容设备回传;
 *       设备不回传则永不产流, 因此对账/onRtpServerTimeout/onStreamChanged 对 TYPE_TALK 豁免）</li>
 *   <li>音频源腿: 浏览器/客户端经 WS(/{@code /ws/talk/{talkId}?token=})二进制直推 G.711A 字节
 *       （getUserMedia→8k 重采样→A 律编码）, 平台 RTP 打包(pt=8)后直发设备应答地址。
 *       <b>平台直推设计</b>(docs/04 §3 备注): 本地 ZLM master 实测对 WebRTC 源 startSendRtp
 *       ES/PS 均不出帧(2026-09-15), 且对讲为双人低并发场景, 平台直发可控且零转码</li>
 * </ul>
 * <p>begin 语义: 等"设备已应答"后置 STREAMING, 音频帧经 WS 到达即发。对讲 UDP 套接字
 * （offer 端口）在 start 时创建、close 时释放, 与会话同生命周期。
 * 状态机 INVITING →（begin）→ STREAMING → 终态; 200 OK 解析与应答地址保存在内存
 * （对讲秒级~分钟级生命周期, 重启丢失后 begin 报错重开, 不落库）。</p>
 */
@Slf4j
@Service
public class TalkSessionService {

    /** 活跃态集合（与 DB 生成列 active_flag 口径一致） */
    private static final Set<String> ACTIVE = Set.of("INIT", "INVITING", "STREAMING", "CLOSING");

    /** begin 等待总时长(秒): 设备应答最多 ~4s + 音频源注册 ~2s, 与点播 INVITE 10s 超时量级对齐 */
    static final int BEGIN_WAIT_SECONDS = 6;

    private final GbMediaSessionMapper sessionMapper;
    private final GbDeviceMapper deviceMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final GbMediaServerMapper serverMapper;
    private final SsrcPoolService ssrcPool;
    private final InviteService invite;
    private final SessionCleanupService cleanup;
    private final StreamEventPublisher publisher;
    private final com.yujian.vision.service.monitor.VisionMetrics metrics;
    private final RedissonClient redisson;

    /** callId → sessionId（对讲 INVITE 响应关联; 与 MediaSessionService.callIndex 互斥不相交） */
    private final Map<String, Long> callIndex = new ConcurrentHashMap<>();
    /** sessionId → 设备 200 OK 应答地址（begin 推流目标; 会话终态/清理时移除） */
    private final Map<Long, DeviceAnswer> answerIndex = new ConcurrentHashMap<>();
    /** sessionId → 对讲 UDP 套接字（offer 端口; 发送 G.711A RTP; 会话同生命周期） */
    private final Map<Long, DatagramSocket> sockets = new ConcurrentHashMap<>();
    /** sessionId → RTP 序号/时间戳（[seq, ts]; G711A 8kHz, ts 按样本数推进） */
    private final Map<Long, long[]> rtpSeqTs = new ConcurrentHashMap<>();

    public TalkSessionService(GbMediaSessionMapper sessionMapper, GbDeviceMapper deviceMapper,
            GbDeviceChannelMapper channelMapper, GbMediaServerMapper serverMapper,
            SsrcPoolService ssrcPool, InviteService invite,
            SessionCleanupService cleanup, StreamEventPublisher publisher,
            com.yujian.vision.service.monitor.VisionMetrics metrics, RedissonClient redisson) {
        this.sessionMapper = sessionMapper;
        this.deviceMapper = deviceMapper;
        this.channelMapper = channelMapper;
        this.serverMapper = serverMapper;
        this.ssrcPool = ssrcPool;
        this.invite = invite;
        this.cleanup = cleanup;
        this.publisher = publisher;
        this.metrics = metrics;
        this.redisson = redisson;
    }

    // ---------------------------------------------------------------- 公共编排

    /**
     * 发起对讲（REST /api/talk/start）：校验 → 设备腿 openRtpServer → 落库 INVITING →
     * INVITE s=Talk。同步返回推送入口, 浏览器随即 WebRTC push + begin。同通道活跃对讲幂等返回。
     */
    public StartInfo start(long channelDbId, Long userId) {
        GbDeviceChannelEntity ch = channelMapper.selectById(channelDbId);
        if (ch == null || ch.getDeletedTime() != null) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "通道不存在: " + channelDbId);
        }
        GbDeviceEntity dev = deviceMapper.selectById(ch.getDeviceDbId());
        if (dev == null || dev.getOnline() == null || dev.getOnline() != 1) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE, "设备离线: " + ch.getChannelId());
        }
        if (ch.getStatus() != null && ch.getStatus() != 1) {
            throw new BizException(ErrorCode.CHANNEL_OFFLINE, "通道离线: " + ch.getChannelId());
        }
        if (dev.getDeviceType() != null && dev.getDeviceType() == 2) {
            // 下级平台通道对讲（平台↔下级的媒体方向翻转）不在本批范围, 显式拒绝防误用
            throw new BizException(ErrorCode.TALK_UNAVAILABLE, "对讲暂不支持级联(下级平台)通道");
        }
        RLock lock = redisson.getLock("gb:lock:talk:" + channelDbId);
        lock.lock();
        try {
            GbMediaSessionEntity active = findActiveTalk(channelDbId);
            if (active != null) {
                log.info("对讲幂等返回既有会话: channel={} talkId={} status={}",
                        channelDbId, active.getId(), active.getStatus());
                return startInfoOf(active, null);
            }
            GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                    .eq(GbMediaServerEntity::getStatus, 1).last("LIMIT 1"));
            if (node == null) {
                throw new BizException(ErrorCode.ZLM_API_FAILED, "无在线流媒体节点");
            }
            String rand = java.lang.Integer.toHexString((int) (System.nanoTime() & 0xFFFF));
            GbMediaSessionEntity s = new GbMediaSessionEntity();
            s.setSessionType(GbMediaSessionEntity.TYPE_TALK);
            s.setStreamId("gb_talk_" + ch.getChannelId() + "_" + rand);
            s.setApp("rtp");
            // audioStreamId/audioPort 列本批预留未用(平台直推设计, 见类注释)
            s.setDeviceDbId(dev.getId());
            s.setChannelDbId(ch.getId());
            s.setDeviceId(dev.getDeviceId());
            s.setChannelId(ch.getChannelId());
            s.setServerId(node.getServerId());
            s.setTcpMode(0);
            s.setStreamProfile(1);
            s.setUserId(userId);
            s.setStatus("INVITING");
            s.setViewerCount(0);
            s.setInviteTime(LocalDateTime.now());
            // 平台直推: offer 端口 = 本地 UDP 套接字端口; c= 取平台到该设备路由的出口地址
            String mediaIp = localMediaIpOf(dev, node);
            try {
                // 对讲 SSRC 与点播同号段(首位 0), live 序列分配防冲突
                s.setSsrc(ssrcPool.allocLive(node.getServerId(), "talk:" + ch.getId()));
                DatagramSocket socket = new DatagramSocket(new InetSocketAddress(0));
                s.setRtpPort(socket.getLocalPort());
                sessionMapper.insert(s);
                sockets.put(s.getId(), socket);
                // 发 INVITE s=Talk（发送前置 sent, 成功率按 answered/failed 分母核算）
                metrics.increment("vision.talk", "result", "sent");
                var handle = invite.sendTalkInvite(s, mediaIp, dev,
                        dev.getIp(), dev.getPort() != null ? dev.getPort() : 5060);
                s.setCallId(handle.callId());
                callIndex.put(handle.callId(), s.getId());
            } catch (BizException e) {
                releasePartial(node.getServerId(), s);
                throw e;
            } catch (Exception e) {
                releasePartial(node.getServerId(), s);
                throw new BizException(ErrorCode.INVITE_FAILED, "会话创建失败: " + e.getMessage());
            }
            sessionMapper.updateById(s);
            log.info("对讲已发起: channel={} stream={} ssrc={} port={} callId={}",
                    channelDbId, s.getStreamId(), s.getSsrc(), s.getRtpPort(), s.getCallId());
            return startInfoOf(sessionMapper.selectById(s.getId()), node);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 开始对讲（REST /api/talk/begin）: 等"设备已应答(200 OK 解析出收流地址) + 音频源已注册到 ZLM"
     * 两条件满足后 startSendRtpAudio（ES 裸流 pt=8）→ STREAMING。
     * 条件更新(INVITING→STREAMING)作单写者闸, 并发/重复 begin 幂等返回。
     */
    public StartInfo begin(long talkId, Long userId) {
        GbMediaSessionEntity s = activeTalkOf(talkId);
        if (!"INVITING".equals(s.getStatus())) {
            return startInfoOf(s, null);
        }
        DeviceAnswer ans = answerIndex.get(talkId);
        long deadline = System.currentTimeMillis() + BEGIN_WAIT_SECONDS * 1000L;
        while (ans == null && System.currentTimeMillis() < deadline) {
            sleepQuietly(200);
            GbMediaSessionEntity cur = sessionMapper.selectById(talkId);
            if (cur == null || SessionCleanupService.isTerminal(cur.getStatus())) {
                throw new BizException(ErrorCode.SESSION_NOT_FOUND, "对讲会话已关闭: " + talkId);
            }
            ans = answerIndex.get(talkId);
        }
        if (ans == null) {
            metrics.increment("vision.talk", "result", "failed");
            throw new BizException(ErrorCode.INVITE_FAILED, "设备未应答对讲 INVITE（或应答 SDP 非法）");
        }
        // 单写者闸: 条件更新 INVITING→STREAMING, 并发/重复 begin 或竞速 stop 时 updated==0 直接返回现状
        int updated = sessionMapper.update(null, new LambdaUpdateWrapper<GbMediaSessionEntity>()
                .eq(GbMediaSessionEntity::getId, talkId)
                .eq(GbMediaSessionEntity::getStatus, "INVITING")
                .set(GbMediaSessionEntity::getStatus, "STREAMING")
                .set(GbMediaSessionEntity::getStartTime, LocalDateTime.now()));
        if (updated == 0) {
            return startInfoOf(sessionMapper.selectById(talkId), null);
        }
        metrics.increment("vision.talk", "result", "talking");
        publisher.talkEvent(s.getStreamId(), s.getChannelId(), "TALK_STARTED", "");
        log.info("对讲已开始: talkId={} stream={} 音频目标={}:{}（WS 音频帧到达即发）",
                talkId, s.getStreamId(), ans.ip(), ans.port());
        return startInfoOf(sessionMapper.selectById(talkId), null);
    }

    /** 结束对讲（REST /api/talk/stop, 设备 BYE, 发送兜底共用收尾）: stopSendRtp → 清理三件套 → 注销音频源流 */
    public void stop(long talkId, String stopReason) {
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || s.getSessionType() == null
                || s.getSessionType() != GbMediaSessionEntity.TYPE_TALK) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "对讲会话不存在: " + talkId);
        }
        if (SessionCleanupService.isTerminal(s.getStatus())) {
            return;
        }
        doClose(s, "CLOSED", stopReason, "");
    }

    /** 会话状态查询（REST /api/talk/status） */
    public StartInfo status(long talkId) {
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || s.getSessionType() == null
                || s.getSessionType() != GbMediaSessionEntity.TYPE_TALK) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "对讲会话不存在: " + talkId);
        }
        return startInfoOf(s, null);
    }

    // ---------------------------------------------------------------- SIP 回调

    /**
     * 对讲 INVITE 的 SIP 响应关联（SipEventRouter RESPONSE 分支调用）:
     * 200 OK → 记 tag/已应答 + 解析设备收流地址缓存; ≥400 → FAILED 清理。
     * 按 callId 过滤（与点播 callIndex 不相交, 无关应答静默忽略）。
     */
    public void onSipResponse(SipEvent event) {
        if (!"INVITE".equals(event.getMethod())) {
            return;
        }
        Long talkId = callIndex.get(event.getCallId());
        if (talkId == null) {
            return;
        }
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || SessionCleanupService.isTerminal(s.getStatus())) {
            callIndex.remove(event.getCallId());
            return;
        }
        int code = event.getStatusCode() == null ? 0 : event.getStatusCode();
        if (code == 200) {
            invite.rememberPeerTag(event.getCallId(), event.getToTag());
            invite.markAnswered(event.getCallId());
            GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
            invite.sendAck(s.getChannelId(), event.getCallId(), dev, dev != null ? dev.getIp() : null,
                    dev != null && dev.getPort() != null ? dev.getPort() : 5060, event.getCseq());
            try {
                TalkAnswerSdp a = TalkAnswerSdp.parse(event.getBody());
                answerIndex.put(talkId, new DeviceAnswer(a.ip(), a.port()));
            } catch (Exception e) {
                // 200 OK 但应答 SDP 非法/缺 PCMA: ACK 照发(协议要求), 会话按不可用收尾
                metrics.increment("vision.talk", "result", "failed");
                log.warn("对讲 200 OK 应答 SDP 非法, 按不可用收尾: talkId={} err={}", talkId, e.getMessage());
                GbMediaSessionEntity fs = s;
                closeAsync(fs, "FAILED", "invalid_answer",
                        "设备应答不支持 G.711A 或 SDP 非法: " + e.getMessage());
                return;
            }
            metrics.increment("vision.talk", "result", "answered");
            log.info("对讲 INVITE 200 OK, ACK 已发: talkId={} callId={} 设备收流地址={}:{}",
                    talkId, event.getCallId(), answerIndex.get(talkId).ip(), answerIndex.get(talkId).port());
        } else if (code >= 400) {
            callIndex.remove(event.getCallId());
            metrics.increment("vision.talk", "result", "failed");
            GbMediaSessionEntity fs = s;
            closeAsync(fs, "FAILED", "invite_rejected", "设备应答 " + code);
        }
    }

    /** 设备主动 BYE（SipEventRouter BYE 分支调用; 点播同款语义） */
    public void onDeviceBye(String callId) {
        Long talkId = callId == null ? null : callIndex.remove(callId);
        if (talkId == null) {
            return;
        }
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || SessionCleanupService.isTerminal(s.getStatus())) {
            return;
        }
        doClose(s, "CLOSED", "device_bye", "设备挂断对讲");
    }


    // ---------------------------------------------------------------- 内部件

    /** 收尾: 关本地套接字 → 本地清理（BYE + SSRC 释放 + 终态落库, 平台直推无 ZLM 对象）→ 事件 */
    private void doClose(GbMediaSessionEntity s, String status, String stopReason, String errorMsg) {
        DatagramSocket socket = sockets.remove(s.getId());
        if (socket != null) {
            socket.close();
        }
        rtpSeqTs.remove(s.getId());
        if (cleanup.closeLocal(s.getId(), status, stopReason,
                errorMsg == null ? "" : errorMsg)) {
            publisher.talkEvent(s.getStreamId(), s.getChannelId(), "TALK_STOPPED", stopReason);
            callIndex.values().removeIf(id -> id.equals(s.getId()));
            answerIndex.remove(s.getId());
        }
    }

    /**
     * WS 音频帧到达（TalkPushWsHandler 调用）: G.711A 裸字节按 ≤160B(20ms@8kHz)分片打
     * RTP 头(pt=8)经对讲套接字直发设备应答地址。非 STREAMING/无应答/无套接字静默丢弃。
     */
    public void onAudioFrame(long talkId, byte[] data, int offset, int len) {
        DatagramSocket socket = sockets.get(talkId);
        DeviceAnswer ans = answerIndex.get(talkId);
        if (socket == null || socket.isClosed() || ans == null) {
            return;
        }
        long[] st = rtpSeqTs.computeIfAbsent(talkId, k -> new long[]{1, 0});
        try {
            InetAddress addr = InetAddress.getByName(ans.ip());
            int pos = offset;
            int remain = len;
            while (remain > 0) {
                int chunk = Math.min(160, remain);
                byte[] pkt = new byte[12 + chunk];
                pkt[0] = (byte) 0x80;                    // V=2
                pkt[1] = (byte) 8;                       // PT=8 (PCMA), 无 marker（连续帧）
                pkt[2] = (byte) ((st[0] >> 8) & 0xFF);   // seq
                pkt[3] = (byte) (st[0] & 0xFF);
                long ts = st[1];
                pkt[4] = (byte) ((ts >> 24) & 0xFF);
                pkt[5] = (byte) ((ts >> 16) & 0xFF);
                pkt[6] = (byte) ((ts >> 8) & 0xFF);
                pkt[7] = (byte) (ts & 0xFF);
                int ssrcInt;
                try {
                    GbMediaSessionEntity s = sessionMapper.selectById(talkId);
                    ssrcInt = s == null || s.getSsrc() == null ? 0 : (int) Long.parseLong(s.getSsrc());
                } catch (NumberFormatException nfe) {
                    ssrcInt = 0;
                }
                pkt[8] = (byte) ((ssrcInt >> 24) & 0xFF);
                pkt[9] = (byte) ((ssrcInt >> 16) & 0xFF);
                pkt[10] = (byte) ((ssrcInt >> 8) & 0xFF);
                pkt[11] = (byte) (ssrcInt & 0xFF);
                System.arraycopy(data, pos, pkt, 12, chunk);
                socket.send(new DatagramPacket(pkt, pkt.length, new InetSocketAddress(addr, ans.port())));
                st[0] = (st[0] + 1) & 0xFFFF;   // seq 16 位回绕
                st[1] += chunk;                 // ts 按样本数推进(8kHz)
                pos += chunk;
                remain -= chunk;
            }
        } catch (Exception e) {
            log.debug("对讲音频帧发送失败(忽略): talkId={} {}", talkId, e.getMessage());
        }
    }

    /** WS 音频源断开（TalkPushWsHandler afterConnectionClosed 调用）: 会话兜底收尾 */
    public void onWsClosed(long talkId) {
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || SessionCleanupService.isTerminal(s.getStatus())) {
            return;
        }
        doClose(s, "FAILED", "stream_lost", "对讲音频源(WS)断开");
    }

    /** 异步清理（SIP dispatch 线程不做阻塞 IO, 同 F4 原则） */
    private void closeAsync(GbMediaSessionEntity s, String status, String stopReason, String errorMsg) {
        asyncPool.execute(() -> {
            try {
                doClose(s, status, stopReason, errorMsg);
            } catch (Exception e) {
                log.error("对讲会话异步清理失败: talkId={} {}", s.getId(), e.getMessage());
            }
        });
    }

    /** 单线程异步池（清理用） */
    private final java.util.concurrent.ExecutorService asyncPool =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "talk-async");
                t.setDaemon(true);
                return t;
            });

    // ---------------------------------------------------------------- 查询与小件

    private GbMediaSessionEntity activeTalkOf(long talkId) {
        GbMediaSessionEntity s = sessionMapper.selectById(talkId);
        if (s == null || s.getSessionType() == null
                || s.getSessionType() != GbMediaSessionEntity.TYPE_TALK
                || SessionCleanupService.isTerminal(s.getStatus())) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND, "对讲会话不存在或已关闭: " + talkId);
        }
        return s;
    }

    private GbMediaSessionEntity findActiveTalk(long channelDbId) {
        List<GbMediaSessionEntity> list = sessionMapper.selectList(
                new LambdaQueryWrapper<GbMediaSessionEntity>()
                        .eq(GbMediaSessionEntity::getChannelDbId, channelDbId)
                        .eq(GbMediaSessionEntity::getSessionType, GbMediaSessionEntity.TYPE_TALK)
                        .in(GbMediaSessionEntity::getStatus, ACTIVE)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** REST 响应体组装（rtcPushUrl 仅在节点在线时拼, 幂等返回/查询场景可缺省） */
    private StartInfo startInfoOf(GbMediaSessionEntity s, GbMediaServerEntity node) {
        // pushWsUrl 由 Controller 依请求构造（ws(s)://host/ws/talk/{talkId}?token=）, 服务层不感知 HTTP
        return new StartInfo(s.getId(), s.getStreamId(), s.getStatus() == null ? "" : s.getStatus(),
                s.getChannelId() == null ? "" : s.getChannelId(), "", s.getAudioPort(), "");
    }

    /** 半途失败: 关本地套接字 + 释放 SSRC（平台直推无 ZLM 对象） */
    private void releasePartial(String serverId, GbMediaSessionEntity s) {
        DatagramSocket socket = sockets.remove(s.getId());
        if (socket != null) {
            socket.close();
        }
        if (s.getSsrc() != null) {
            ssrcPool.release(serverId, s.getSsrc());
        }
    }

    /**
     * 平台侧媒体出口 IP（SDP offer c= 用）：经到设备 SIP 地址的路由探测本机出口地址;
     * 探测失败回退节点 publicIp。设备与平台同 LAN 场景下即平台自身地址。
     */
    private String localMediaIpOf(GbDeviceEntity dev, GbMediaServerEntity node) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(
                    InetAddress.getByName(dev.getIp()), dev.getPort() != null ? dev.getPort() : 5060));
            String ip = probe.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("0.")) {
                return ip;
            }
        } catch (Exception e) {
            log.warn("探测平台媒体出口地址失败, 回退节点 publicIp: {}", e.getMessage());
        }
        return node != null ? node.getPublicIp() : null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 值对象

    /** 设备 200 OK 应答的收流地址（begin 的推流目标） */
    record DeviceAnswer(String ip, int port) {
    }

    /** REST 响应体: talkId/streamId/status/channelId/rtcPushUrl/audioPort（docs/03 §5.x） */
    public record StartInfo(long talkId, String streamId, String status, String channelId,
            String rtcPushUrl, Integer audioPort, String reason) {
    }

    /**
     * 设备对讲应答 SDP 解析（200 OK body）: c=/m=audio/y= 必备;
     * m=audio 行 payload 必须含 8（PCMA）——设备只认 0(PCMU)/96 时本平台不做转码, 按不可用收尾。
     */
    record TalkAnswerSdp(String ip, int port, String ssrc, boolean pcma) {

        static TalkAnswerSdp parse(String sdp) {
            if (sdp == null || sdp.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答 SDP 为空");
            }
            String ip = null;
            Integer port = null;
            String ssrc = null;
            boolean pcma = false;
            for (String line : sdp.split("\r\n")) {
                String l = line.trim();
                if (l.startsWith("c=IN IP4 ")) {
                    ip = l.substring("c=IN IP4 ".length()).trim();
                } else if (l.startsWith("m=audio ") && port == null) {
                    String[] tokens = l.split("\s+");
                    if (tokens.length < 4 || !tokens[2].contains("RTP/AVP")) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答 m= 行非法: " + l);
                    }
                    try {
                        port = Integer.valueOf(tokens[1]);
                    } catch (NumberFormatException e) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答 m= 端口非法: " + tokens[1]);
                    }
                    for (int i = 3; i < tokens.length; i++) {
                        if ("8".equals(tokens[i])) {
                            pcma = true;
                        }
                    }
                } else if (l.startsWith("y=")) {
                    ssrc = l.substring(2).trim();
                }
            }
            if (ip == null || ip.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答缺少 c= 连接地址");
            }
            if (port == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答缺少 m=audio 媒体行");
            }
            if (!pcma) {
                throw new BizException(ErrorCode.BAD_REQUEST, "对讲应答未包含 PCMA(8), 设备不支持 G.711A 对讲");
            }
            return new TalkAnswerSdp(ip, port, ssrc, pcma);
        }
    }
}
