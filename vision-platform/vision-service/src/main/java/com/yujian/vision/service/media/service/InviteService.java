package com.yujian.vision.service.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import com.yujian.vision.service.device.entity.GbDeviceEntity;
import com.yujian.vision.service.media.entity.GbMediaSessionEntity;
import com.yujian.vision.service.media.entity.GbMediaServerEntity;
import com.yujian.vision.service.media.mapper.GbMediaServerMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 点播信令发送层（docs/05 §4）：INVITE/ACK/BYE/INFO/re-INVITE。
 * 对话 tag 由本层生成并保管（callId → [fromTag, toTag]）, BYE/ACK/INFO/re-INVITE 复用构成同对话请求。
 */
@Slf4j
@Service
public class InviteService {

    private final SipTransportProvider transport;
    private final GbPlatformProperties platform;
    private final GbMediaServerMapper serverMapper;
    private final Map<String, String[]> tagStore = new ConcurrentHashMap<>();
    /** 已收到 2xx 最终响应的对话（重传 200 OK 据此补发 ACK; 超时扫描据此判定"已应答", 与响应索引解耦） */
    private final java.util.Set<String> answeredCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public InviteService(SipTransportProvider transport, GbPlatformProperties platform,
            GbMediaServerMapper serverMapper) {
        this.transport = transport;
        this.platform = platform;
        this.serverMapper = serverMapper;
    }

    /** 外呼栈选择: 优先设备注册传输协议(UDP/TCP)对应的栈, 未启用该协议栈时回退平台默认栈 */
    private String outboundStackId(GbDeviceEntity dev) {
        String sid = transport.stackIdForTransport(dev != null ? dev.getTransport() : null);
        return sid != null ? sid : platform.getStackId();
    }

    /** 发 INVITE, 返回对话句柄（callId + fromTag）。栈按设备注册传输协议选择 */
    public InviteHandle sendInvite(GbMediaSessionEntity s, GbMediaServerEntity node, GbDeviceEntity dev,
            String deviceIp, int devicePort) {
        String callId = SipIds.newCallId();
        String fromTag = SipIds.newTag();
        tagStore.put(callId, new String[]{fromTag, null});
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("INVITE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(s.getChannelId() + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(fromTag)
                .sn(SipIds.nextSn())
                .body(sdpOf(s, node.getPublicIp(), null))
                .contentType("APPLICATION/SDP")
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
        return new InviteHandle(callId, fromTag);
    }

    /** 发语音对讲 INVITE（M3-B5, s=Talk + m=audio）, 返回对话句柄。tag 登记与点播同库, ACK/BYE/forgetTags 全复用。
     *  mediaIp=SDP c=/o= 用平台媒体出口地址（平台直推: 本地套接字所绑地址, 由调用方探测） */
    public InviteHandle sendTalkInvite(GbMediaSessionEntity s, String mediaIp, GbDeviceEntity dev,
            String deviceIp, int devicePort) {
        String callId = SipIds.newCallId();
        String fromTag = SipIds.newTag();
        tagStore.put(callId, new String[]{fromTag, null});
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("INVITE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(s.getChannelId() + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(fromTag)
                .sn(SipIds.nextSn())
                .body(SdpTemplates.talkUdp(platform.getServerId(), mediaIp, s.getRtpPort(), s.getSsrc()))
                .contentType("APPLICATION/SDP")
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
        return new InviteHandle(callId, fromTag);
    }

    /** SDP 由会话类型/收流模式决定（docs/04 §4 / docs/07 §2）：实况 s=Play t=0 0;
     *  回放 s=Playback t=起止 epoch 秒。startOverride 供回放 SEEK 传新起点（null=用会话窗口起点）。 */
    private String sdpOf(GbMediaSessionEntity s, String publicIp, java.time.LocalDateTime startOverride) {
        boolean tcp = s.getTcpMode() != null && s.getTcpMode() == 1;
        if (s.getSessionType() != null && s.getSessionType() == GbMediaSessionEntity.TYPE_PLAYBACK) {
            long from = epochOf(startOverride != null ? startOverride : s.getPlaybackStart());
            long to = epochOf(s.getPlaybackEnd());
            return tcp
                    ? SdpTemplates.playbackTcp(platform.getServerId(), publicIp, s.getRtpPort(), s.getSsrc(), from, to)
                    : SdpTemplates.playbackUdp(platform.getServerId(), publicIp, s.getRtpPort(), s.getSsrc(), from, to);
        }
        return tcp
                ? SdpTemplates.playTcp(platform.getServerId(), publicIp, s.getRtpPort(), s.getSsrc())
                : SdpTemplates.playUdp(platform.getServerId(), publicIp, s.getRtpPort(), s.getSsrc());
    }

    /** LocalDateTime → Unix 秒（SDP t= 行, 设备按本机时区解释, 厂商差异列入 B6 兼容性） */
    private static long epochOf(java.time.LocalDateTime time) {
        return time == null ? 0L
                : time.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 无状态 ACK（2xx 后直发, 复用对话 tag）。栈按设备传输协议选择。
     * 入参 inviteCseq = 所应答 INVITE 的 CSeq（200 OK 响应头携带）: RFC 3261 §13.2.2.4 要求 ACK 回显
     * 请求 CSeq（B2 评审修复: 此前用全局 SN, 初次 INVITE 与 re-INVITE(SEEK) 的 200 OK 均 ACK 失配,
     * 严格设备会持续重传 2xx 乃至对话收尾）。初发/re-INVITE/重传 200 OK 均同值回显, 幂等。
     */
    public void sendAck(String channelId, String callId, GbDeviceEntity dev, String deviceIp, int devicePort,
            Long inviteCseq) {
        String[] t = tagStore.get(callId);
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("ACK")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(channelId + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(t != null ? t[0] : null)
                .toTag(t != null ? t[1] : null)
                .sn(inviteCseq != null ? inviteCseq : SipIds.nextSn())
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
    }

    /** 发 BYE（同对话, tag 复用）。无 tag 守卫: 对话已结束/进程重启 tag 丢失时不发不合规范 BYE (C5.1)。栈按设备传输协议选择 */
    public void sendBye(String channelId, String callId, GbDeviceEntity dev, String deviceIp, int devicePort) {
        String[] t = tagStore.get(callId);
        if (t == null) {
            log.warn("BYE 缺少对话 tag(对话已结束或重启后重建), 跳过发送: callId={}", callId);
            return;
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("BYE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(channelId + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(t[0])
                .toTag(t[1])
                .sn(SipIds.nextSn())
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
    }

    /** 发 INFO（对话内请求, 回放控制 MANSRTSP, docs/07 §3）。
     *  tag 复用 mirror sendBye; 无 tag 守卫: 对话已结束/进程重启 tag 丢失时不发不合规范请求 (C5.1)。栈按设备传输协议选择 */
    public void sendInfo(String channelId, String callId, GbDeviceEntity dev, String deviceIp, int devicePort, String body) {
        String[] t = tagStore.get(callId);
        if (t == null) {
            log.warn("INFO 缺少对话 tag(对话已结束或重启后重建), 跳过发送: callId={}", callId);
            return;
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("INFO")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(channelId + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(t[0])
                .toTag(t[1])
                .inDialog(true)
                .body(body)
                .contentType(ManScdpConstants.MANSRTSP_CONTENT_TYPE)
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
    }

    /** 发对话内 re-INVITE（回放 SEEK 拖动, docs/07 §3 策略 B）: 端口/SSRC/CallID 均不变, t=行=新起点~原终点。
     *  经 Dialog 发送 → CSeq 由栈自增, 200 OK 由 MediaSessionService.onSipResponse 补 ACK。
     *  无 tag 守卫同 sendBye。栈按设备传输协议选择 */
    public void sendReInvite(GbMediaSessionEntity s, String callId, GbDeviceEntity dev,
            String deviceIp, int devicePort, java.time.LocalDateTime startOverride) {
        String[] t = tagStore.get(callId);
        if (t == null) {
            log.warn("re-INVITE 缺少对话 tag(对话已结束或重启后重建), 跳过发送: callId={}", callId);
            return;
        }
        GbMediaServerEntity node = serverMapper.selectOne(new LambdaQueryWrapper<GbMediaServerEntity>()
                .eq(GbMediaServerEntity::getServerId, s.getServerId()).last("LIMIT 1"));
        if (node == null) {
            throw new BizException(ErrorCode.ZLM_API_FAILED, "承载节点不存在: " + s.getServerId());
        }
        SipMessageContext ctx = SipMessageContext.builder()
                .stackId(outboundStackId(dev))
                .method("INVITE")
                .from(platform.getServerId() + "@" + platform.getDomain())
                .to(s.getChannelId() + "@" + platform.getDomain())
                .callId(callId)
                .fromTag(t[0])
                .toTag(t[1])
                .inDialog(true)
                .body(sdpOf(s, node.getPublicIp(), startOverride))
                .contentType("APPLICATION/SDP")
                .targetHost(deviceIp)
                .targetPort(devicePort)
                .build();
        transport.send(ctx);
    }

    /** 记录对端 tag（INVITE 200 OK To tag） */
    public void rememberPeerTag(String callId, String toTag) {
        String[] t = tagStore.get(callId);
        if (t != null && toTag != null) {
            t[1] = toTag;
        }
    }

    /** 记录对话已应答（收到 2xx 最终响应）。null 安全：无 callId 直接忽略 */
    public void markAnswered(String callId) {
        if (callId != null) {
            answeredCallIds.add(callId);
        }
    }

    /** 对话是否已应答（收到过 2xx 最终响应） */
    public boolean isAnswered(String callId) {
        return callId != null && answeredCallIds.contains(callId);
    }

    /** 对话结束后移除 tag。对话关闭即一并清理"已应答"标记, 防跨对话串扰 */
    public void forgetTags(String callId) {
        tagStore.remove(callId);
        answeredCallIds.remove(callId);
    }

    /** 对话句柄 */
    public record InviteHandle(String callId, String fromTag) {
    }
}
