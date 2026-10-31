package com.yujian.vision.service.cascade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yujian.vision.common.security.AesCipher;
import com.yujian.vision.gb28181.auth.DigestAuthenticator;
import com.yujian.vision.gb28181.codec.ManScdpCodec;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import com.yujian.vision.service.cascade.mapper.GbCascadeUpperMapper;
import com.yujian.vision.sip.transport.SipEvent;
import com.yujian.vision.sip.transport.SipIds;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 级联 UA 状态机（本平台作为下级, docs/08 §2.1）：多上级并行注册、周期注册刷新、
 * Keepalive 心跳、断链指数退避重连（5s 起倍增, 上限 300s）。
 * 运行态在 {@link CascadeUpperSession}（内存）；在线状态变化回写 gb_cascade_upper。
 * 扫描式调度（单 @Scheduled tick 每 5s）, 不为每上级建线程, 风格同 CatalogSyncService.sweep。
 */
@Slf4j
@Component
public class CascadeUpperManager {

    /** 注册应答超时（秒） */
    @Value("${vision.cascade.register-timeout-seconds:10}")
    private int registerTimeoutSeconds;

    /** 退避初始秒数/上限（倍增） */
    @Value("${vision.cascade.backoff-initial-seconds:5}")
    private int backoffInitialSeconds;
    @Value("${vision.cascade.backoff-max-seconds:300}")
    private int backoffMaxSeconds;

    private final GbCascadeUpperMapper upperMapper;
    private final CascadeSipSender sender;
    private final ManScdpCodec codec;
    private final AesCipher aes;
    private final CascadeInviteService cascadeInvites;
    private final CascadeAlarmService cascadeAlarms;
    /** SIP 主备（M3-B6 阶段2）: 备台身份停跑注册/心跳状态机（无栈必失败且打乱退避状态机） */
    private final com.yujian.vision.service.ha.HaRuntimeService ha;

    /** upperId → 运行态会话 */
    private final Map<Long, CascadeUpperSession> sessions = new ConcurrentHashMap<>();
    /** REGISTER 在途索引: callId → upperId（RESPONSE 路由用, SipEventRouter 先于媒体资源查询） */
    private final Map<String, Long> registerIndex = new ConcurrentHashMap<>();
    /** Keepalive 在途索引: callId → upperId */
    private final Map<String, Long> keepaliveIndex = new ConcurrentHashMap<>();

    public CascadeUpperManager(GbCascadeUpperMapper upperMapper, CascadeSipSender sender,
            ManScdpCodec codec, AesCipher aes, CascadeInviteService cascadeInvites,
            CascadeAlarmService cascadeAlarms,
            com.yujian.vision.service.ha.HaRuntimeService ha) {
        this.upperMapper = upperMapper;
        this.sender = sender;
        this.codec = codec;
        this.aes = aes;
        this.cascadeInvites = cascadeInvites;
        this.cascadeAlarms = cascadeAlarms;
        this.ha = ha;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        loadAll();
    }

    @PreDestroy
    public void shutdown() {
        // 尽量优雅注销（best-effort, 不阻塞停机）
        for (CascadeUpperSession s : sessions.values()) {
            try {
                GbCascadeUpperEntity u = s.getUpper();
                sender.sendRegister(u, sender.stackFor(u), s.getRegisterCallId(), s.getRegisterSn() + 1, null, 0);
            } catch (Exception e) {
                log.debug("级联注销跳过: upperId={}", s.getUpperId(), e);
            }
        }
    }

    /** 启动/恢复：按库内启用配置重建会话（配置停用的顺带置 offline） */
    public void loadAll() {
        List<GbCascadeUpperEntity> enabled = upperMapper.selectList(new LambdaQueryWrapper<GbCascadeUpperEntity>()
                .eq(GbCascadeUpperEntity::getStatus, 1));
        Set<Long> ids = enabled.stream().map(GbCascadeUpperEntity::getId).collect(java.util.stream.Collectors.toSet());
        for (GbCascadeUpperEntity u : enabled) {
            getOrCreate(u);
        }
        for (Long id : new ArrayList<>(sessions.keySet())) {
            if (!ids.contains(id)) {
                dropSession(id, "配置停用");
            }
        }
        log.info("级联 UA 启动: {} 个上级注册会话", sessions.size());
    }

    /** 配置变更后由管理端调用：新建/原地更新/停用移除 */
    public void reload(Long upperId) {
        GbCascadeUpperEntity u = upperMapper.selectById(upperId);
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            dropSession(upperId, "配置停用");
            return;
        }
        CascadeUpperSession s = sessions.get(upperId);
        if (s == null) {
            getOrCreate(u);
        } else {
            s.setUpper(u);
        }
    }

    private CascadeUpperSession getOrCreate(GbCascadeUpperEntity u) {
        return sessions.compute(u.getId(), (id, exist) -> {
            if (exist != null) {
                exist.setUpper(u);
                return exist;
            }
            CascadeUpperSession s = new CascadeUpperSession(id, u);
            s.setState(CascadeUpperSession.ST_BACKOFF);
            s.setBackoffSeconds(0);
            s.setNextRetryAt(0);
            return s;
        });
    }

    private void dropSession(Long upperId, String reason) {
        CascadeUpperSession s = sessions.remove(upperId);
        if (s != null && s.getRegisterCallId() != null) {
            registerIndex.remove(s.getRegisterCallId());
        }
        dbOnline(upperId, 0, null);
        // B2 阶段2: 上级配置停用/移除 → 回收其在途转推（未收 BYE 场景兜底）; B4 阶段2: 同清理其报警订阅
        if (s != null) {
            try {
                cascadeInvites.onUpperGone(s.getUpper().getUpperDeviceId());
                cascadeAlarms.onUpperGone(s.getUpper().getUpperDeviceId());
            } catch (Exception e) {
                log.warn("级联停用清理转推/订阅异常(忽略): upperId={} err={}", upperId, e.getMessage());
            }
        }
        if (s != null) {
            log.info("级联会话移除: upperId={} ({})", upperId, reason);
        }
    }

    /** SIP 应答入口（SipEventRouter 对 RESPONSE 先查本管理器, 命中即消费不再下发） */
    public boolean onSipResponse(SipEvent event) {
        String callId = event.getCallId();
        if (callId == null) {
            return false;
        }
        Long registerUpperId = registerIndex.get(callId);
        Long keepaliveUpperId = keepaliveIndex.remove(callId);
        if (registerUpperId == null && keepaliveUpperId == null) {
            return false;
        }
        try {
            if (registerUpperId != null) {
                onRegisterResponse(registerUpperId, callId, event);
            } else if (keepaliveUpperId != null && event.getStatusCode() != null && event.getStatusCode() == 200) {
                CascadeUpperSession s = sessions.get(keepaliveUpperId);
                if (s != null) {
                    long now = System.currentTimeMillis();
                    s.setLastKeepaliveAckAt(now);
                    s.setNextKeepaliveAt(now + intervalMillis(s.getUpper()));
                }
            }
        } catch (Exception e) {
            log.error("级联应答处理异常: upperId={} {}", registerUpperId, event.getStatusCode(), e);
        }
        return true;
    }

    private void onRegisterResponse(Long upperId, String callId, SipEvent event) {
        CascadeUpperSession s = sessions.get(upperId);
        if (s == null || !callId.equals(s.getRegisterCallId())) {
            registerIndex.remove(callId);
            return;
        }
        int code = event.getStatusCode() == null ? 0 : event.getStatusCode();
        if (code >= 200 && code < 300) {
            registerIndex.remove(callId);
            long now = System.currentTimeMillis();
            synchronized (s) {
                s.setState(CascadeUpperSession.ST_ONLINE);
                s.setBackoffSeconds(0);
                s.setNextRegisterAt(now + expiresMillis(s.getUpper()));
                s.setNextKeepaliveAt(now);
                s.setLastKeepaliveAckAt(now);
            }
            dbOnline(upperId, 1, now);
            log.info("级联注册成功: {} -> {}:{} (expires={}s)",
                    s.getUpper().getLocalDeviceId(), s.getUpper().getUpperIp(), s.getUpper().getUpperPort(),
                    s.getUpper().getExpires());
            return;
        }
        if (code == 401 && event.getWwwAuthenticate() != null && !s.isAuthTried()) {
            s.setAuthTried(true);
            try {
                String password = resolvePassword(s.getUpper());
                String auth = DigestAuthenticator.buildAuthorization(event.getWwwAuthenticate(), "REGISTER",
                        CascadeSipSender.requestUri(s.getUpper()), s.getUpper().getLocalDeviceId(), password);
                long sn = s.getRegisterSn() + 1;
                s.setRegisterSn(sn);
                sender.sendRegister(s.getUpper(), sender.stackFor(s.getUpper()), callId, sn, auth,
                        s.getUpper().getExpires());
                s.setSentAt(System.currentTimeMillis());
                log.debug("级联注册携带 Digest 重试: upperId={} cseq={}", upperId, sn);
                return;
            } catch (Exception e) {
                registerIndex.remove(callId);
                toBackoff(s, "401 挑战处理失败: " + e.getMessage());
                return;
            }
        }
        registerIndex.remove(callId);
        toBackoff(s, "注册失败: HTTP " + code + (s.isAuthTried() ? "(已带鉴权, 疑似密码错误)" : ""));
    }

    /**
     * 状态机扫描（5s）：注册应答超时/注册刷新/心跳发送/心跳判死/退避重连。
     */
    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        if (!ha.sipGatesOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CascadeUpperSession s : sessions.values()) {
            try {
                tickSession(s, now);
            } catch (Exception e) {
                log.error("级联会话 tick 异常: upperId={}", s.getUpperId(), e);
            }
        }
        // 清理无应答的 keepalive 在途索引（防泄漏）
        keepaliveIndex.entrySet().removeIf(e -> sessions.get(e.getValue()) == null);
    }

    private void tickSession(CascadeUpperSession s, long now) {
        GbCascadeUpperEntity u = s.getUpper();
        switch (s.getState()) {
            case CascadeUpperSession.ST_WAIT_CHALLENGE -> {
                if (now - s.getSentAt() > registerTimeoutSeconds * 1000L) {
                    if (s.getRegisterCallId() != null) {
                        registerIndex.remove(s.getRegisterCallId());
                    }
                    toBackoff(s, "注册应答超时");
                }
            }
            case CascadeUpperSession.ST_ONLINE -> {
                if (now >= s.getNextRegisterAt()) {
                    sendRegister(s, false);
                } else if (now >= s.getNextKeepaliveAt()) {
                    sendKeepalive(s);
                } else if (s.getLastKeepaliveAckAt() > 0
                        && now - s.getLastKeepaliveAckAt() > 3 * intervalMillis(u)) {
                    // 连续 3 个心跳周期无应答 → 判死重连（对齐设备侧离线判定思想）
                    toBackoff(s, "心跳超时(" + (now - s.getLastKeepaliveAckAt()) / 1000 + "s 无应答)");
                }
            }
            case CascadeUpperSession.ST_BACKOFF -> {
                if (now >= s.getNextRetryAt()) {
                    sendRegister(s, false);
                }
            }
            default -> toBackoff(s, "未知状态 " + s.getState());
        }
    }

    private void sendRegister(CascadeUpperSession s, boolean authorizedRetry) {
        GbCascadeUpperEntity u = s.getUpper();
        String callId = SipIds.newCallId();
        long sn = SipIds.nextSn();
        s.setRegisterCallId(callId);
        s.setRegisterSn(sn);
        s.setAuthTried(authorizedRetry);
        registerIndex.put(callId, s.getUpperId());
        sender.sendRegister(u, sender.stackFor(u), callId, sn, null, normalized(u).getExpires());
        synchronized (s) {
            s.setState(CascadeUpperSession.ST_WAIT_CHALLENGE);
            s.setSentAt(System.currentTimeMillis());
        }
    }

    private void sendKeepalive(CascadeUpperSession s) {
        GbCascadeUpperEntity u = normalized(s.getUpper());
        long sn = SipIds.nextSn();
        String callId = sender.sendMessage(u, codec.buildKeepalive(u.getLocalDeviceId(), sn), sn);
        keepaliveIndex.put(callId, s.getUpperId());
        s.setNextKeepaliveAt(System.currentTimeMillis() + intervalMillis(u));
    }

    /**
     * 会话失效统一入口（注册应答超时/注册失败/心跳判死）→ 退避重连 + 在途转推回收
     * （B2 阶段 2: 上级会话失效=视为离线, 未收 BYE 的在途转推由 {@link CascadeInviteService#onUpperGone}
     * 回收; onUpperGone 内部各步独立 try/catch, 此处不再包一层, 异常由 tick 的兜底 catch 兜住）。
     */
    private void toBackoff(CascadeUpperSession s, String reason) {
        synchronized (s) {
            s.setState(CascadeUpperSession.ST_BACKOFF);
            int next = s.getBackoffSeconds() <= 0 ? backoffInitialSeconds : Math.min(s.getBackoffSeconds() * 2, backoffMaxSeconds);
            s.setBackoffSeconds(next);
            s.setNextRetryAt(System.currentTimeMillis() + next * 1000L);
        }
        dbOnline(s.getUpperId(), 0, null);
        log.warn("级联会话进入退避: upperId={} {}s后重试 ({})", s.getUpperId(), s.getBackoffSeconds(), reason);
        // B2 阶段 2: 上级会话失效 → 回收其未收 BYE 的在途转推; B4 阶段2: 同清其报警订阅
        try {
            cascadeInvites.onUpperGone(s.getUpper().getUpperDeviceId());
            cascadeAlarms.onUpperGone(s.getUpper().getUpperDeviceId());
        } catch (Exception e) {
            log.warn("级联断链清理转推/订阅异常(忽略): upperId={} err={}", s.getUpperId(), e.getMessage());
        }
    }

    /** 在线状态回写（只 set online/register_time, 状态变化时才触发） */
    private void dbOnline(Long upperId, int online, Long nowMillis) {
        try {
            LambdaUpdateWrapper<GbCascadeUpperEntity> up = new LambdaUpdateWrapper<GbCascadeUpperEntity>()
                    .eq(GbCascadeUpperEntity::getId, upperId)
                    .set(GbCascadeUpperEntity::getOnline, online);
            if (nowMillis != null) {
                up.set(GbCascadeUpperEntity::getRegisterTime,
                        new java.sql.Timestamp(nowMillis).toLocalDateTime());
            }
            upperMapper.update(null, up);
        } catch (Exception e) {
            log.warn("级联在线状态回写失败: upperId={}", upperId, e);
        }
    }

    private String resolvePassword(GbCascadeUpperEntity u) {
        String stored = u.getPassword();
        if (stored == null || stored.isBlank()) {
            return "";
        }
        // AesCipher.decrypt 对存量明文兼容原样返回（启动 sweep 未触达的新行也不出错）
        return aes.decrypt(stored);
    }

    private GbCascadeUpperEntity normalized(GbCascadeUpperEntity u) {
        if (u.getExpires() == null) {
            u.setExpires(3600);
        }
        if (u.getKeepaliveInterval() == null) {
            u.setKeepaliveInterval(60);
        }
        return u;
    }

    private long expiresMillis(GbCascadeUpperEntity u) {
        return normalized(u).getExpires() * 800L;
    }

    private long intervalMillis(GbCascadeUpperEntity u) {
        return normalized(u).getKeepaliveInterval() * 1000L;
    }

    /** 该编码是否为已启用上级（上级来包识别, 每包查库——上级来包频率低, 可接受） */
    public boolean isEnabledUpper(String upperDeviceId) {
        if (upperDeviceId == null || upperDeviceId.isBlank()) {
            return false;
        }
        return upperMapper.selectCount(new LambdaQueryWrapper<GbCascadeUpperEntity>()
                .eq(GbCascadeUpperEntity::getUpperDeviceId, upperDeviceId)
                .eq(GbCascadeUpperEntity::getStatus, 1)) > 0;
    }

    /** 供管理端展示的在线快照: upperId → {state, backoffSeconds} */
    public Map<Long, Map<String, Object>> snapshot() {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (CascadeUpperSession s : sessions.values()) {
            result.put(s.getUpperId(), Map.of(
                    "state", s.getState(),
                    "backoffSeconds", s.getBackoffSeconds()));
        }
        return result;
    }
}
