package com.yujian.vision.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.springframework.stereotype.Service;

/**
 * 在线会话管理（Redis）。
 * <p>键设计：
 * <ul>
 *   <li>{@code vision:auth:token:{jti}} → 会话信息 JSON, TTL=token 硬上限 24h</li>
 *   <li>{@code vision:auth:idle:{jti}} → 心跳标记, TTL=空闲超时 2h, 每次请求续期</li>
 *   <li>{@code vision:auth:user-tokens:{userId}} → 该用户 jti 索引（改密/封禁全量吊销用）</li>
 * </ul>
 * 吊销 = 删 token+idle 两键；有效 = 两键均存在。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineSessionService {

    public static final String TOKEN_PREFIX = "vision:auth:token:";
    public static final String IDLE_PREFIX = "vision:auth:idle:";
    public static final String USER_TOKENS_PREFIX = "vision:auth:user-tokens:";

    public record SessionInfo(Long userId, String username, String nickname, String loginIp, long loginTime) {
    }

    public record OnlineUserInfo(String tokenId, Long userId, String username, String nickname,
            String loginIp, long loginTime, long idleRemainSeconds) {
    }

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final JwtProperties props;
    private final com.yujian.vision.web.ws.WsSessionRegistry wsRegistry;

    /** 登录成功后建会话 */
    public SessionInfo create(String jti, Long userId, String username, String nickname, String loginIp) {
        SessionInfo info = new SessionInfo(userId, username, nickname, loginIp, System.currentTimeMillis());
        try {
            RBucket<String> token = redisson.getBucket(TOKEN_PREFIX + jti);
            token.set(objectMapper.writeValueAsString(info), Duration.ofHours(props.getExpireHours()));
            redisson.getBucket(IDLE_PREFIX + jti).set("1", Duration.ofHours(props.getIdleHours()));
            RSet<String> index = redisson.getSet(USER_TOKENS_PREFIX + userId);
            index.add(jti);
            index.expire(Duration.ofDays(2));
        } catch (Exception e) {
            throw new IllegalStateException("创建登录会话失败", e);
        }
        return info;
    }

    /** 读取会话（token 键被吊销/过期返回 null） */
    public SessionInfo get(String jti) {
        String json = redisson.<String>getBucket(TOKEN_PREFIX + jti).get();
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SessionInfo.class);
        } catch (Exception e) {
            log.warn("会话信息解析失败, jti={}", jti);
            return null;
        }
    }

    /** 空闲键续期；键不存在（空闲超时）返回 false */
    public boolean refreshIdle(String jti) {
        return redisson.getBucket(IDLE_PREFIX + jti)
                .expire(Duration.ofHours(props.getIdleHours()));
    }

    /** 吊销单会话（登出/强制下线）：删 Redis 键 + 踢 WS 连接 */
    public void revoke(String jti) {
        String json = redisson.<String>getBucket(TOKEN_PREFIX + jti).getAndDelete();
        redisson.getBucket(IDLE_PREFIX + jti).delete();
        if (json != null) {
            try {
                SessionInfo info = objectMapper.readValue(json, SessionInfo.class);
                redisson.getSet(USER_TOKENS_PREFIX + info.userId()).remove(jti);
                wsRegistry.closeByToken(jti);
            } catch (Exception ignored) {
                // 会话信息损坏时仅清键, 索引残留由索引 TTL 兜底
            }
        }
    }

    /** 吊销某用户全部会话（改密/封禁） */
    public void revokeAll(Long userId) {
        RSet<String> index = redisson.getSet(USER_TOKENS_PREFIX + userId);
        for (String jti : index.readAll()) {
            redisson.getBucket(TOKEN_PREFIX + jti).delete();
            redisson.getBucket(IDLE_PREFIX + jti).delete();
            wsRegistry.closeByToken(jti);
        }
        index.delete();
    }

    /** 在线会话列表（admin 会话监控页）。空闲(2h)已超时的会话在认证层已不可用,
     *  这里顺手清理残留键并跳过展示, 避免死会话挂满列表直到 24h 硬上限 */
    public List<OnlineUserInfo> list() {
        List<OnlineUserInfo> list = new ArrayList<>();
        RKeys keys = redisson.getKeys();
        // getKeysByPattern 系列在 Redisson 3.45 已全部 @Deprecated, 改用 KeysScanOptions 形式
        // （同为 SCAN 游标遍历, limit 不设=全量; chunkSize 仅影响每次 SCAN 往返的键数）
        for (String key : keys.getKeys(KeysScanOptions.defaults()
                .pattern(TOKEN_PREFIX + "*").chunkSize(1000))) {
            String jti = key.substring(TOKEN_PREFIX.length());
            String json = redisson.<String>getBucket(key).get();
            if (json == null) {
                continue;
            }
            try {
                SessionInfo s = objectMapper.readValue(json, SessionInfo.class);
                long idleRemain = redisson.getBucket(IDLE_PREFIX + jti).remainTimeToLive();
                if (idleRemain <= 0) {
                    redisson.getBucket(key).delete();
                    redisson.getBucket(IDLE_PREFIX + jti).delete();
                    continue;
                }
                list.add(new OnlineUserInfo(jti, s.userId(), s.username(), s.nickname(),
                        s.loginIp(), s.loginTime(), idleRemain / 1000));
            } catch (Exception ignored) {
                // 跳过损坏条目
            }
        }
        return list;
    }
}
