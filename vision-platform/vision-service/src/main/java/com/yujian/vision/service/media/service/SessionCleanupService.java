package com.yujian.vision.service.media.service;

import com.yujian.vision.media.zlm.ZlmApiClient;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.mapper.GbMediaSessionMapper;
import com.yujian.vision.service.device.mapper.GbDeviceMapper;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 会话清理三件套（docs/06 §2.1 规则）：BYE → closeRtpServer → 释放 SSRC, 幂等可重入。
 * 用户停止 / none_reader / 断流 / 超时 / 崩溃恢复 全部收敛到本类（docs/04 §1）。
 *
 * B2 加固: ①返回是否实际执行了清理（true=本次由活跃态转为终态）——同一会话被多个触发源
 * 并发清理时, 只有第一个执行者应对外发布 streamClosed 事件, 后续触发(如设备 BYE 引发的
 * ZLM unregister hook 竞速到达)不得覆盖首个执行者落库的 stopReason; ②closing 在途集合同样
 * 用于拦截"终态落库前"窗口内的重复清理(行状态尚未提交, 仅靠 isTerminal 挡不住)。
 */
@Slf4j
@Service
public class SessionCleanupService {

    private final GbMediaSessionMapper sessionMapper;
    private final GbDeviceMapper deviceMapper;
    private final ZlmApiClient zlm;
    private final SsrcPoolService ssrcPool;
    private final InviteService invite;
    /** M3-B6 阶段1: 跨节点拉流代理清理 */
    private final StreamProxyService streamProxies;
    /** 清理在途会话（终态落库前防重复清理/重复发事件） */
    private final Set<Long> closing = ConcurrentHashMap.newKeySet();

    public SessionCleanupService(GbMediaSessionMapper sessionMapper, GbDeviceMapper deviceMapper,
            ZlmApiClient zlm, SsrcPoolService ssrcPool, InviteService invite,
            StreamProxyService streamProxies) {
        this.sessionMapper = sessionMapper;
        this.deviceMapper = deviceMapper;
        this.zlm = zlm;
        this.ssrcPool = ssrcPool;
        this.invite = invite;
        this.streamProxies = streamProxies;
    }

    /**
     * 幂等清理: 终态一次性生效（仅当仍处于活跃态）。
     * @return true=本次由活跃态清理为终态（调用方应据此对外发 streamClosed）;
     *         false=会话已终态/不存在/清理已在途（重复触发, 不应再发事件）
     * @param stopReason user_stop/none_reader/stream_lost/invite_timeout/invite_rejected/no_stream/device_offline
     */
    public boolean close(long sessionId, String status, String stopReason, String errorMsg) {
        return closeInternal(sessionId, status, stopReason, errorMsg, true, false, true);
    }

    /**
     * 本地媒体清理（M3-B5 对讲, 平台直推无 ZLM 对象）: BYE → 释放 SSRC → 终态落库,
     * 跳过 closeRtpServer/close_streams（对讲不占 ZLM 资源）。
     */
    public boolean closeLocal(long sessionId, String status, String stopReason, String errorMsg) {
        return closeInternal(sessionId, status, stopReason, errorMsg, true, false, false);
    }

    /**
     * 崩溃恢复专用（docs/06 §8）：重启后 SIP 对话 tag 已随进程丢失（内存 tagStore 清空），
     * 规范的对话内 BYE 无法构造，跳过信令直接清理；ZLM 侧可能仍挂着已注册的流，
     * 追加 close_streams 强制注销（AC：平台重启后无孤儿流）。
     */
    public boolean closeAfterRestart(long sessionId, String status, String stopReason, String errorMsg) {
        return closeInternal(sessionId, status, stopReason, errorMsg, false, true, true);
    }

    /** 同上, tryZlm=false 时跳过 ZLM 远端清理（节点预检不可达时的快速降级, 防拖死启动） */
    public boolean closeAfterRestart(long sessionId, String status, String stopReason, String errorMsg, boolean tryZlm) {
        return closeInternal(sessionId, status, stopReason, errorMsg, false, true, tryZlm);
    }

    private boolean closeInternal(long sessionId, String status, String stopReason, String errorMsg,
            boolean withBye, boolean closeStream, boolean tryZlm) {
        GbMediaSessionEntity s = sessionMapper.selectById(sessionId);
        if (s == null || isTerminal(s.getStatus())) {
            return false;
        }
        if (!closing.add(sessionId)) {
            log.debug("会话清理已在途, 跳过重复触发: session={}", sessionId);
            return false;
        }
        try {
            String current = s.getStatus();
        // 1. BYE（对话存在才发; 对端可能已死, 发送失败继续清理）
        if (withBye && s.getCallId() != null && !s.getCallId().isBlank()) {
            try {
                GbDeviceEntity dev = deviceMapper.selectById(s.getDeviceDbId());
                if (dev != null && dev.getIp() != null) {
                    invite.sendBye(s.getChannelId(), s.getCallId(), dev, dev.getIp(),
                            dev.getPort() != null ? dev.getPort() : 5060);
                }
            } catch (Exception e) {
                log.warn("BYE 发送失败(继续清理): session={}, {}", sessionId, e.getMessage());
            }
            invite.forgetTags(s.getCallId());
        }
        // 2. closeRtpServer
        if (tryZlm) {
            try {
                zlm.closeRtpServer(s.getServerId(), s.getStreamId());
            } catch (Exception e) {
                log.warn("closeRtpServer 失败(继续清理): session={}, {}", sessionId, e.getMessage());
            }
            // 2b. close_streams（崩溃恢复路径: 注销 ZLM 已注册的流, 幂等, 无流时为空操作）
            if (closeStream) {
                try {
                    zlm.closeStreams(s.getServerId(), s.getApp() == null ? "rtp" : s.getApp(), s.getStreamId());
                } catch (Exception e) {
                    log.warn("close_streams 失败(继续清理): session={}, {}", sessionId, e.getMessage());
                }
            }
        } else {
            log.warn("节点不可达, 跳过 ZLM 远端清理(由对账/none_reader 兜底): session={} server={}",
                    sessionId, s.getServerId());
        }
        // 2c. 跨节点拉流代理清理（M3-B6 阶段1）: 源流已关, 各分发节点上的代理随之拆除
        try {
            streamProxies.cleanupForStream(s.getStreamId());
        } catch (Exception e) {
            log.warn("跨节点代理清理失败(继续清理): stream={}, {}", s.getStreamId(), e.getMessage());
        }
        // 3. 释放 SSRC
        try {
            ssrcPool.release(s.getServerId(), s.getSsrc());
        } catch (Exception e) {
            log.warn("SSRC 释放失败(继续清理): session={}, {}", sessionId, e.getMessage());
        }
        // 4. 状态落库
        GbMediaSessionEntity up = new GbMediaSessionEntity();
        up.setId(s.getId());
        up.setStatus(status);
        up.setStopReason(stopReason);
        up.setErrorMsg(errorMsg == null ? "" : errorMsg);
        up.setStopTime(java.time.LocalDateTime.now());
        sessionMapper.updateById(up);
        log.info("会话已清理: id={} stream={} {} -> {} ({})", sessionId, s.getStreamId(), current, status, stopReason);
            return true;
        } finally {
            closing.remove(sessionId);
        }
    }

    /** 是否终态 */
    public static boolean isTerminal(String status) {
        return "CLOSED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
