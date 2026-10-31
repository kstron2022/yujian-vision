package com.yujian.vision.service.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.SmCipher;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.entity.OpenAppEntity;
import com.yujian.vision.service.openapi.mapper.OpenAppMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 第三方接入应用管理（docs/15 步骤2）：KEY 签发 / secret 重置 / 白名单 / 数据范围 / 频控 / 启停 / 有效期。
 * app_secret 明文仅在创建/重置时返回一次（AppCredential）, 库中恒为 SM4 密文, 分页/详情恒脱敏。
 * 仅 vision.openapi.enabled=true 时装配（D7: 未启用零行为）。
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vision.openapi.enabled", havingValue = "true")
public class OpenAppService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 无易混淆字符(0/O/1/l/I)的 57 字符集, 48 位 ≈ 283bit 熵 */
    private static final char[] SECRET_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int APP_KEY_BYTES = 16;
    private static final int SECRET_LEN = 48;

    private final OpenAppMapper appMapper;
    private final SmCipher smCipher;

    /** 创建凭据（明文 secret 仅此一次, 由 controller 原样返回前端一次性展示） */
    public record AppCredential(Long appId, String appKey, String appSecret) {
    }

    /** 分页查询（管理页; app_secret 密文不出服务层, 恒置 null） */
    public IPage<OpenAppEntity> page(int page, int count, String appName, Integer status) {
        IPage<OpenAppEntity> result = appMapper.selectPage(new Page<>(page, count),
                new LambdaQueryWrapper<OpenAppEntity>()
                        .like(StringUtils.hasText(appName), OpenAppEntity::getAppName, appName)
                        .eq(status != null, OpenAppEntity::getStatus, status)
                        .orderByDesc(OpenAppEntity::getId));
        result.getRecords().forEach(e -> e.setAppSecret(null));
        return result;
    }

    /** 详情（编辑回显; 同样脱敏） */
    public OpenAppEntity get(Long id) {
        OpenAppEntity e = appMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        e.setAppSecret(null);
        return e;
    }

    /** 新建：app_key=32 位随机 hex, secret=48 位随机字符 SM4 落库; 返回明文凭据(仅此一次) */
    public AppCredential create(String appName, String allowedApis, String channelScope,
            Integer rateLimit, LocalDateTime expireTime) {
        if (!StringUtils.hasText(appName)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "应用名称不能为空");
        }
        OpenAppEntity e = new OpenAppEntity();
        e.setAppName(appName.trim());
        e.setAllowedApis(normalizeCsv(allowedApis));
        e.setChannelScope(normalizeCsv(channelScope));
        e.setRateLimit(rateLimit == null || rateLimit <= 0 ? OpenAppAuth.DEFAULT_RATE_LIMIT : rateLimit);
        e.setExpireTime(expireTime);
        e.setStatus(1);
        String plainSecret = randomSecret();
        e.setAppKey(newAppKey());
        e.setAppSecret(smCipher.encrypt(plainSecret));
        appMapper.insert(e);
        return new AppCredential(e.getId(), e.getAppKey(), plainSecret);
    }

    /** 重置 secret：换新随机值落库; 返回明文(仅此一次)。旧 secret 立即失效 */
    public AppCredential resetSecret(Long id) {
        OpenAppEntity e = requireEntity(id);
        String plainSecret = randomSecret();
        OpenAppEntity patch = new OpenAppEntity();
        patch.setId(id);
        patch.setAppSecret(smCipher.encrypt(plainSecret));
        appMapper.updateById(patch);
        return new AppCredential(id, e.getAppKey(), plainSecret);
    }

    /** 编辑基础信息（不含 secret/状态, 启停走 updateStatus） */
    public void update(Long id, String appName, String allowedApis, String channelScope,
            Integer rateLimit, LocalDateTime expireTime) {
        requireEntity(id);
        if (!StringUtils.hasText(appName)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "应用名称不能为空");
        }
        OpenAppEntity patch = new OpenAppEntity();
        patch.setId(id);
        patch.setAppName(appName.trim());
        patch.setAllowedApis(normalizeCsv(allowedApis));
        patch.setChannelScope(normalizeCsv(channelScope));
        patch.setRateLimit(rateLimit == null || rateLimit <= 0 ? OpenAppAuth.DEFAULT_RATE_LIMIT : rateLimit);
        patch.setExpireTime(expireTime);
        appMapper.updateById(patch);
    }

    /** 启停 */
    public void updateStatus(Long id, Integer status) {
        requireEntity(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法状态值");
        }
        OpenAppEntity patch = new OpenAppEntity();
        patch.setId(id);
        patch.setStatus(status);
        appMapper.updateById(patch);
    }

    /** 删除（物理删; 调用日志保留） */
    public void delete(Long id) {
        requireEntity(id);
        appMapper.deleteById(id);
    }

    /** 按 appKey 加载启用中的应用 → 鉴权域模型（步骤3 过滤器验签入口; 过期/停用返回 null） */
    public OpenAppAuth loadAuth(String appKey, String algorithm) {
        OpenAppEntity e = appMapper.selectOne(new LambdaQueryWrapper<OpenAppEntity>()
                .eq(OpenAppEntity::getAppKey, appKey));
        if (e == null || e.getStatus() == null || e.getStatus() != 1) {
            return null;
        }
        // 有效期: null=永久; 已过期视同停用（docs/15 步骤3 校验口径）
        if (e.getExpireTime() != null && e.getExpireTime().isBefore(LocalDateTime.now())) {
            return null;
        }
        return OpenAppAuth.from(e, smCipher.decrypt(e.getAppSecret()), algorithm);
    }

    private OpenAppEntity requireEntity(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "id 不能为空");
        }
        OpenAppEntity e = appMapper.selectById(id);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        return e;
    }

    /** 32 位随机 hex（128bit 熵; uk_app_key 唯一, 碰撞概率可忽略） */
    private String newAppKey() {
        byte[] b = new byte[APP_KEY_BYTES];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 48 位随机字符 */
    private String randomSecret() {
        StringBuilder sb = new StringBuilder(SECRET_LEN);
        for (int i = 0; i < SECRET_LEN; i++) {
            sb.append(SECRET_CHARS[RANDOM.nextInt(SECRET_CHARS.length)]);
        }
        return sb.toString();
    }

    /** CSV 归一化: 去空白项/合并空段; null → 空串 */
    private String normalizeCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return "";
        }
        return String.join(",", java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }
}
