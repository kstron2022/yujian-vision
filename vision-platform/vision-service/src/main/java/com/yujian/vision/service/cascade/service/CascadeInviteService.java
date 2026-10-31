package com.yujian.vision.service.cascade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.cascade.entity.GbCascadeSharedChannelEntity;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeSharedChannelMapper;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.service.device.entity.GbDeviceChannelEntity;
import com.yujian.vision.service.device.mapper.GbDeviceChannelMapper;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.media.service.MediaSessionService;
import com.yujian.vision.service.media.service.SdpTemplates;
import com.yujian.vision.service.media.service.SessionCleanupService;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipTransportProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 级联点播转推（本平台作为下级, docs/08 §2.3）：上级 INVITE 共享通道 → 反查本平台真实通道 →
 * 内部复用点播链路建/取源流（play: 已有 STREAMING 实况直接复用, 否则向本地设备点播）→
 * 轮询等源流 STREAMING → 按上级 SDP（c=/m=/y=/a=setup）反向协商 startSendRtp /
 * startSendRtpPassive 向上级转推 → 200 OK 携 SDP 应答。上级 BYE 或上级注销时回收转推并
 * 退订源流（viewer 归零自动关流, docs/08 §2.3 停止语义）。
 *
 * <p>编排约束（docs/08 §5 上级 INVITE 超时短的应对）：源流就绪等待是 500ms 轮询 sleep,
 * 不占 SIP dispatch 线程（F4 同原则）—— onUpperInvite 先回 100 Trying, 编排投递
 * cascade-invite 单线程池异步完成应答; 就绪超时回 504
 * （{@code vision.cascade.invite-ready-timeout-seconds}, 默认 10s）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeInviteService {

    /** 等源流就绪的超时秒数（超时回 504, docs/08 §2.3） */
    @Value("${vision.cascade.invite-ready-timeout-seconds:10}")
    private int readyTimeoutSeconds;

    /** 就绪轮询间隔（ms） */
    private static final long POLL_INTERVAL_MS = 500;

    private final GbCascadeUpperMapper upperMapper;
    private final GbCascadeSharedChannelMapper sharedMapper;
    private final GbDeviceChannelMapper channelMapper;
    private final MediaSessionService mediaSessionService;
    private final ZlmApiClient zlm;
    private final SipTransportProvider transport;
    /**
     * 在线 ZLM 节点查询（转推 serverId 来源, docs/04 §3）：PlayResult 不携带 serverId,
     * ZlmNodeRegistry 又只有按 serverId 反查, 故直查 gb_media_server —— 选路查询与
     * MediaSessionService.inviteNew 完全一致（status=1 取首条）, 单节点部署必然同节点。
     */
    private final GbMediaServerMapper serverMapper;
    /** M3-B6 F3: 转推源节点取会话落库节点（M3 挂账已清偿）, 不再重查在线节点 */
    private final GbMediaSessionMapper sessionMapper;

    /** 在途转推索引（callId → 上下文; 上级 BYE / 上级注销时回收） */
    private final ConcurrentHashMap<String, SendContext> sending = new ConcurrentHashMap<>();

    /**
     * 应答编排线程池（单线程 + daemon）：等待源流就绪是轮询 sleep 阻塞操作,
     * 不能占 SIP dispatch 线程（F4 同原则）; 单线程串行化避免多路 INVITE 并发点播同一通道时
     * 的资源竞争毛刺（点播本体另有 Redisson 通道锁兜底）。
     */
    private final ExecutorService inviteExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cascade-invite");
        t.setDaemon(true);
        return t;
    });

    /**
     * 上级 INVITE 入口（docs/08 §2.3）：非上级来源回 603 维持原拒绝行为; 通道反查三级
     * （共享虚拟编码 → 共享原编码 → catalog_mode=2 全部通道）未命中回 404; SDP 非法回 400。
     * 合法请求先回 100 Trying, 编排投递 cascade-invite 线程池异步完成应答。
     */
    public void onUpperInvite(SipEvent event) {
        GbCascadeUpperEntity u = findByUpperDeviceId(event.getFromUser());
        if (u == null) {
            log.debug("非上级来源的点播请求, 拒绝: fromUser={}", event.getFromUser());
            transport.sendResponse(event, 603, Map.of());
            return;
        }
        // 国标上级 INVITE 的目标通道编码在 Request-URI user（与 To 头可能不一致）, 缺失回退 To 头 user
        String targetId = event.getRequestUser();
        if (targetId == null || targetId.isBlank()) {
            targetId = event.getToUser();
        }
        Long channelDbId = targetId == null || targetId.isBlank() ? null : resolveChannelDbId(u, targetId);
        if (channelDbId == null) {
            log.info("上级点播通道未共享, 回 404: upper={} target={}", u.getUpperDeviceId(), targetId);
            transport.sendResponse(event, 404, Map.of());
            return;
        }
        UpperSdp sdp;
        try {
            sdp = UpperSdp.parse(event.getBody());
        } catch (BizException e) {
            log.info("上级 INVITE SDP 非法, 回 400: upper={} err={}", u.getUpperDeviceId(), e.getMessage());
            transport.sendResponse(event, 400, Map.of());
            return;
        }
        transport.sendResponse(event, 100, Map.of());
        String finalTargetId = targetId;
        inviteExecutor.execute(() -> {
            try {
                doAnswer(u, event, channelDbId, sdp, finalTargetId);
            } catch (Exception e) {
                // 兜底: 编排线程不允许带异常消亡（漏应答由上级 INVITE 超时兜底）
                log.error("级联点播编排异常: upper={} target={}", u.getUpperDeviceId(), finalTargetId, e);
            }
        });
    }

    /**
     * 上级 BYE（docs/08 §2.3 停止）：按 callId 命中在途转推 → 200 应答 → 停转推 → 退订源流
     * （viewer 归零自动关流）。未命中返回 false（交回原 BYE 处理链）。
     */
    public boolean onUpperBye(SipEvent event) {
        SendContext ctx = event.getCallId() == null ? null : sending.remove(event.getCallId());
        if (ctx == null) {
            return false;
        }
        transport.sendResponse(event, 200, Map.of());
        try {
            zlm.stopSendRtp(ctx.serverId(), "rtp", ctx.streamId(), ctx.ssrc());
        } catch (Exception e) {
            log.warn("级联停止转推失败(残留由 ZLM 自愈): stream={} err={}", ctx.streamId(), e.getMessage());
        }
        try {
            mediaSessionService.stop(ctx.streamId(), null);
        } catch (Exception e) {
            log.warn("级联源流退订失败(会话可能已终态): stream={} err={}", ctx.streamId(), e.getMessage());
        }
        log.info("级联转推已停止(上级 BYE): upper={} 通道({}) stream={} ssrc={}",
                ctx.upperDeviceId(), ctx.localChannelId(), ctx.streamId(), ctx.ssrc());
        return true;
    }

    /**
     * 上级注销/下线清理（级联 UA 状态机离线时调用, docs/08 §2.1）：回收该上级全部在途转推,
     * 各步独立 try/catch —— 单路失败不影响其余清理。
     */
    public void onUpperGone(String upperDeviceId) {
        if (upperDeviceId == null || upperDeviceId.isBlank()) {
            return;
        }
        for (Map.Entry<String, SendContext> e : sending.entrySet()) {
            if (!upperDeviceId.equals(e.getValue().upperDeviceId())) {
                continue;
            }
            if (!sending.remove(e.getKey(), e.getValue())) {
                continue;   // 已被并发 BYE/清理取走
            }
            SendContext ctx = e.getValue();
            try {
                zlm.stopSendRtp(ctx.serverId(), "rtp", ctx.streamId(), ctx.ssrc());
            } catch (Exception ex) {
                log.warn("上级注销停转推失败(残留由 ZLM 自愈): stream={} err={}", ctx.streamId(), ex.getMessage());
            }
            try {
                mediaSessionService.stop(ctx.streamId(), null);
            } catch (Exception ex) {
                log.warn("上级注销退订源流失败(会话可能已终态): stream={} err={}", ctx.streamId(), ex.getMessage());
            }
            log.info("级联转推已回收(上级注销): upper={} 通道({}) stream={}",
                    ctx.upperDeviceId(), ctx.localChannelId(), ctx.streamId());
        }
    }

    /** 是否存在在途级联转推（监控用） */
    public boolean isUpperSending() {
        return !sending.isEmpty();
    }

    /** 在途级联转推路数（监控用） */
    public int sendingCount() {
        return sending.size();
    }

    /**
     * ZLM on_send_rtp_stopped 兜底（M3-B2 阶段 2）：上级未发 BYE 即断链（TCP 断开等
     * 发送异常, ZLM 自停发送并回调本入口）→ 按 stream+ssrc 匹配在途转推回收。
     * 正常 BYE 路径上下文已被 {@link #onUpperBye} 移除, 此处匹配不到即忽略（幂等）;
     * ssrc 为空时按 streamId 全量匹配（同流多路转推一并回收, 此时流要停必然全停）。
     */
    public void onSendRtpStopped(String streamId, String ssrc) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        for (Map.Entry<String, SendContext> e : sending.entrySet()) {
            SendContext ctx = e.getValue();
            if (!streamId.equals(ctx.streamId())) {
                continue;
            }
            // ssrc 为空时按 streamId 全量匹配（同流多路转推一并回收）
            if (ssrc != null && !ssrc.isBlank() && !ssrc.equals(ctx.ssrc())) {
                continue;
            }
            if (!sending.remove(e.getKey(), ctx)) {
                continue;   // 已被并发 BYE/清理取走
            }
            // ZLM 已自停发送, 只需退订源流（viewer 归零自动关流, docs/08 §2.3 停止语义）
            releaseSource(ctx.streamId());
            log.info("级联转推已回收(发送端断开): upper={} 通道({}) stream={} ssrc={}",
                    ctx.upperDeviceId(), ctx.localChannelId(), ctx.streamId(), ctx.ssrc());
        }
    }

    // ---------------------------------------------------------------- 私有

    /**
     * 应答编排（cascade-invite 线程, docs/08 §2.3 步骤 2~5）：建/取源流 → 等就绪 →
     * 反向 setup 转推 → 200 OK(SDP 应答) + 登记在途索引。
     * 失败路径：源流不可建（设备/通道离线等 BizException）→ 404; 就绪超时或终态 → 504;
     * 转推失败 → 488。失败时退订本服务经 play() 占用的源流观看名额, 防泄漏。
     */
    private void doAnswer(GbCascadeUpperEntity u, SipEvent event, Long channelDbId, UpperSdp sdp, String targetId) {
        MediaSessionService.PlayResult play;
        try {
            // 内部复用点播链路建源流（docs/08 §2.3 步骤 2: 已有 STREAMING 实况直接复用并 viewer+1）。
            // tcpMode=0（UDP 拉流）: 源流段(设备→ZLM)与转推段(上级 SDP 协商)相互独立, UDP 起流最快,
            // 设备侧 TCP 源流的兼容性属于设备接入域（模拟器 sim_device_hold 亦只支持 UDP 推流）
            play = mediaSessionService.play(channelDbId, 0, 1, null, null); // perms=null: 上级转推为内部调用, 跳过 cascade:play 校验
        } catch (BizException e) {
            log.info("级联源流建立失败, 回 404: upper={} target={} channelDbId={} err={}",
                    u.getUpperDeviceId(), targetId, channelDbId, e.getMessage());
            transport.sendResponse(event, 404, Map.of());
            return;
        }
        String streamId = play.streamId();
        String ssrc = sdp.ssrc();
        // 复用会话可能已 STREAMING（play 复用路径）, 此时免轮询; 否则等源流就绪, 超时/终态回 504
        String status = play.status();
        if (!"STREAMING".equals(status)) {
            status = awaitStreaming(play.sessionId(),
                    System.currentTimeMillis() + readyTimeoutSeconds * 1000L);
        }
        if (!"STREAMING".equals(status)) {
            log.info("级联源流未就绪, 回 504: upper={} stream={} status={} 超时={}s",
                    u.getUpperDeviceId(), streamId, status, readyTimeoutSeconds);
            releaseSource(streamId);
            transport.sendResponse(event, 504, Map.of());
            return;
        }
        // TCP 按 a=setup 反向应答（docs/08 §5: 对端 active→我 passive / 对端 passive 或缺失→我 active;
        // UDP 无 setup 行, answerSetup=null）
        String answerSetup = null;
        if (sdp.tcp()) {
            boolean upperActive = sdp.setup() != null && "active".equalsIgnoreCase(sdp.setup());
            answerSetup = upperActive ? "passive" : "active";
        }
        String serverId;
        try {
            // M3-B6 F3: 转推源节点 = 会话落库节点（邀请时调度器选定并落库）, 多节点下转推必须
            // 指向真正持有流的节点; 查无会话行(理论不可达)才回落在线节点查询
            serverId = resolveSessionServerId(play.sessionId());
            if (answerSetup == null) {
                // UDP: ZLM 直接向上级收流地址转发
                zlm.startSendRtp(serverId, "rtp", streamId, ssrc, sdp.ip(), sdp.port());
            } else if ("passive".equals(answerSetup)) {
                // 上级 setup=active: ZLM 作 TCP server 监听, 等上级连接取流
                zlm.startSendRtpPassive(serverId, "rtp", streamId, ssrc);
            } else {
                // 上级 setup=passive/缺失: ZLM 作 client 主动连上级监听端口
                // （2026-09-25 LiveGBS 联调修复: 此前误走 startSendRtp 硬编码 is_udp=1 实发 UDP,
                // 对端 TCP 收流恒 none rtp data receive——startSendRtpTcp 即 is_udp=0）
                zlm.startSendRtpTcp(serverId, "rtp", streamId, ssrc, sdp.ip(), sdp.port());
            }
        } catch (Exception e) {
            log.warn("级联转推失败, 回 488: upper={} stream={} err={}", u.getUpperDeviceId(), streamId, e.getMessage());
            releaseSource(streamId);
            transport.sendResponse(event, 488, Map.of());
            return;
        }
        GbDeviceChannelEntity ch = channelMapper.selectById(channelDbId);
        String localChannelId = ch != null && ch.getChannelId() != null ? ch.getChannelId() : targetId;
        // 200 OK 携 SDP 应答: 媒体参数全部回显上级收流参数, a=recvonly→sendonly, TCP setup 反向（docs/08 §2.3）
        transport.sendResponse(event, 200, Map.of(),
                SdpTemplates.cascadeAnswer(u.getLocalDeviceId(), sdp.ip(), sdp.port(), ssrc,
                        sdp.payloadTypes(), sdp.rtpmapLines(), answerSetup),
                "application/sdp");
        sending.put(event.getCallId(), new SendContext(u.getId(), u.getUpperDeviceId(), streamId,
                ssrc, serverId, localChannelId));
        log.info("级联点播转推已建立: upper={} 通道({}) stream={} ssrc={} mode={} server={}",
                u.getUpperDeviceId(), localChannelId, streamId, ssrc,
                answerSetup == null ? "UDP" : "TCP-" + answerSetup, serverId);
    }

    /**
     * 轮询源流状态直到 STREAMING / 终态 / 超时（500ms 间隔, docs/08 §2.3 步骤 2）。
     * 状态读取走 MediaSessionService.getSessionStatus(sessionId) 主键精确单查
     * （M3-B2 挂账已清偿: 原 listSessions 最近 100 条窗口近似在会话被挤出窗口时误判
     * 未就绪, 高并发新建下可能误 504, 该路径已移除）。
     */
    private String awaitStreaming(long sessionId, long deadlineMillis) {
        while (System.currentTimeMillis() < deadlineMillis) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }
            String status = sessionStatus(sessionId);
            if ("STREAMING".equals(status) || SessionCleanupService.isTerminal(status)) {
                return status;
            }
        }
        return "TIMEOUT";
    }

    /** 单会话状态读取（主键精确查询; 查询失败/会话不存在返回 null 继续等待） */
    private String sessionStatus(long sessionId) {
        try {
            return mediaSessionService.getSessionStatus(sessionId);
        } catch (Exception e) {
            log.warn("级联轮询会话状态失败(继续等待): session={} err={}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 失败路径回收: 退订本服务 play() 占用的源流观看名额（viewer 归零自动关流, docs/08 §2.3） */
    private void releaseSource(String streamId) {
        try {
            mediaSessionService.stop(streamId, null);
        } catch (Exception e) {
            log.warn("级联源流退订失败(会话可能已终态): stream={} err={}", streamId, e.getMessage());
        }
    }

    /**
     * M3-B6 F3（M3 挂账已清偿）: 转推源节点 = 会话落库节点。点播调度器 inviteNew 选定节点并落库
     * gb_media_session.server_id, 转推 startSendRtp 必须指向真正持有流的节点; 查无会话行才回落
     * {@link #resolveOnlineNodeServerId()}（历史兜底, 理论不可达）。
     */
    private String resolveSessionServerId(Long sessionId) {
        GbMediaSessionEntity s = sessionMapper.selectById(sessionId);
        if (s != null && StringUtils.hasText(s.getServerId())) {
            return s.getServerId();
        }
        return resolveOnlineNodeServerId();
    }

    /**
     * 在线 ZLM 节点 serverId（F3 兜底）：与点播调度器同一「status=1 取首条」查询。
     * 多节点下仅作会话行缺失时的兜底（正常路径不会走到）。
     */
    private String resolveOnlineNodeServerId() {
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getStatus, 1)
                .last("LIMIT 1"));
        if (node == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "无在线流媒体节点");
        }
        return node.getServerId();
    }

    /**
     * 上级 INVITE 编码 → 本平台通道 dbId（docs/08 §2.3 步骤 1, 反查顺序）：
     * ① 共享表虚拟编码（virtual_device_id）直查; ② 共享范围内按原编码（channel_id）匹配;
     * ③ catalog_mode=2（全部通道目录模式）按原编码直查通道表。均未命中返回 null（回 404）。
     */
    /** 同包 {@link CascadePtzService} 的 DeviceControl 透传复用同一反查（B2 阶段 3） */
    Long resolveChannelDbId(GbCascadeUpperEntity u, String targetId) {
        // ① 虚拟编码 → 共享表直查
        List<GbCascadeSharedChannelEntity> byVirtual = sharedMapper.selectList(
                new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                        .eq(GbCascadeSharedChannelEntity::getUpperId, u.getId())
                        .eq(GbCascadeSharedChannelEntity::getVirtualDeviceId, targetId)
                        .eq(GbCascadeSharedChannelEntity::getEnabled, 1)
                        .last("LIMIT 1"));
        if (!byVirtual.isEmpty()) {
            return byVirtual.get(0).getChannelDbId();
        }
        // ② 原编码: 共享表圈定范围后按 gb_device_channel.channel_id 匹配
        List<Long> sharedIds = sharedMapper.selectList(new LambdaQueryWrapper<GbCascadeSharedChannelEntity>()
                .eq(GbCascadeSharedChannelEntity::getUpperId, u.getId())
                .eq(GbCascadeSharedChannelEntity::getEnabled, 1))
                .stream().map(GbCascadeSharedChannelEntity::getChannelDbId).toList();
        if (!sharedIds.isEmpty()) {
            List<GbDeviceChannelEntity> hit = channelMapper.selectList(
                    new LambdaQueryWrapper<GbDeviceChannelEntity>()
                            .in(GbDeviceChannelEntity::getId, sharedIds)
                            .eq(GbDeviceChannelEntity::getChannelId, targetId)
                            .isNull(GbDeviceChannelEntity::getDeletedTime)
                            .last("LIMIT 1"));
            if (!hit.isEmpty()) {
                return hit.get(0).getId();
            }
        }
        // ③ catalog_mode=2: 目录上报了全部通道, 原编码直接放行
        if (u.getCatalogMode() != null && u.getCatalogMode() == 2) {
            List<GbDeviceChannelEntity> hit = channelMapper.selectList(
                    new LambdaQueryWrapper<GbDeviceChannelEntity>()
                            .eq(GbDeviceChannelEntity::getChannelId, targetId)
                            .isNull(GbDeviceChannelEntity::getDeletedTime)
                            .last("LIMIT 1"));
            if (!hit.isEmpty()) {
                return hit.get(0).getId();
            }
        }
        return null;
    }

    /** 上级配置查询（upperDeviceId 精确 + status=1 启用, 与 CascadeCatalogService 同口径） */
    /** 同包 {@link CascadePtzService} 复用（DeviceControl 来包识别上级） */
    GbCascadeUpperEntity findByUpperDeviceId(String upperDeviceId) {
        if (upperDeviceId == null || upperDeviceId.isBlank()) {
            return null;
        }
        List<GbCascadeUpperEntity> list = upperMapper.selectList(
                new LambdaQueryWrapper<GbCascadeUpperEntity>()
                        .eq(GbCascadeUpperEntity::getUpperDeviceId, upperDeviceId)
                        .eq(GbCascadeUpperEntity::getStatus, 1));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 上级 INVITE SDP 收流参数（docs/08 §2.3 步骤 3）。
     *
     * @param ip           c= 连接地址（上级收流地址）
     * @param port         m= 收流端口
     * @param ssrc         y= 指定的收流 SSRC（转推按此发送）
     * @param tcp          m= 行是否 TCP/RTP/AVP
     * @param setup        a=setup 值（active/passive, UDP 或缺失为 null）
     * @param payloadTypes m= 行 payload 列表（应答原样回显）
     * @param rtpmapLines  rtpmap 行原样列表（应答原样回显）
     * @param sessionName  s= 会话名（Play, 仅记录不参与转发）
     */
    record UpperSdp(String ip, int port, String ssrc, boolean tcp, String setup,
            List<String> payloadTypes, List<String> rtpmapLines, String sessionName) {

        /**
         * 解析上级 INVITE SDP：按行 split("\r\n") 取 c=/m=/y=/a=setup/s=/rtpmap。
         * c=/m=/y= 为应答必备, 缺失抛 BAD_REQUEST; a=setup 与 s= 允许缺失
         * （UDP 无 setup; TCP 时 setup 缺失按对端 actpass 对待, RFC 4145 应答方默认
         * 取 active 即我方主动连, 见 doAnswer 分流; s= 不参与应答转发）。
         */
        static UpperSdp parse(String sdp) {
            if (sdp == null || sdp.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: 消息体为空");
            }
            String ip = null;
            Integer port = null;
            boolean tcp = false;
            List<String> payloadTypes = List.of();
            String ssrc = null;
            String setup = null;
            String sessionName = null;
            List<String> rtpmapLines = new ArrayList<>();
            for (String line : sdp.split("\r\n")) {
                String l = line.trim();
                if (l.startsWith("c=IN IP4 ")) {
                    ip = l.substring("c=IN IP4 ".length()).trim();
                } else if (l.startsWith("m=video ") && port == null) {
                    // 首条视频媒体行: m=video {port} [TCP/]RTP/AVP {payload...}
                    String[] tokens = l.split("\\s+");
                    if (tokens.length < 4 || !tokens[2].contains("RTP/AVP")) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: m= 行非 RTP/AVP 视频: " + l);
                    }
                    try {
                        port = Integer.valueOf(tokens[1]);
                    } catch (NumberFormatException e) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: m= 端口非法: " + tokens[1]);
                    }
                    tcp = tokens[2].contains("TCP/");
                    payloadTypes = List.copyOf(Arrays.asList(tokens).subList(3, tokens.length));
                } else if (l.startsWith("y=")) {
                    ssrc = l.substring(2).trim();
                } else if (l.startsWith("a=setup:")) {
                    setup = l.substring("a=setup:".length()).trim();
                } else if (l.startsWith("s=")) {
                    sessionName = l.substring(2).trim();
                } else if (l.startsWith("a=rtpmap:")) {
                    rtpmapLines.add(l);   // rtpmap 行原样回显（docs/08 §5: 严格按上级 SDP 回显协商）
                }
            }
            if (ip == null || ip.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: 缺少 c= 连接地址");
            }
            if (port == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: 缺少 m=video 媒体行");
            }
            if (ssrc == null || ssrc.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上级 INVITE SDP 非法: 缺少 y= SSRC");
            }
            return new UpperSdp(ip, port, ssrc, tcp, setup, payloadTypes,
                    List.copyOf(rtpmapLines), sessionName);
        }
    }

    /** 在途转推上下文（callId → 上下文; 上级 BYE / 上级注销回收用） */
    record SendContext(long upperId, String upperDeviceId, String streamId, String ssrc,
            String serverId, String localChannelId) {
    }
}
