package com.yujian.vision.service.openapi.domain;

import com.yujian.vision.service.openapi.entity.OpenAppEntity;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 开放接口鉴权域模型（docs/15 步骤2/3）：签名过滤器验签通过后构造,
 * principal 塞入 OpenApiAuthenticationToken, 开放接口按 allowedApis/channelScope 做授权与数据范围过滤。
 * 字段全部不可变; appSecret 为解密后明文, 仅服务内存验签用, 严禁落日志/出响应。
 */
@Getter
public class OpenAppAuth {

    /** QPS 频控默认值（应用未配/配了非法值时兜底; 2026-09-26 由 10 上调——点播+轮询场景 10 过紧） */
    public static final int DEFAULT_RATE_LIMIT = 50;

    private final Long appId;
    private final String appKey;
    /** 解密后明文（HMAC 验签密钥） */
    private final String appSecret;
    /** 已解析 API 白名单; 空 = 仅只读接口（docs/15 步骤3 授权口径） */
    private final Set<String> allowedApis;
    /** 已解析可见分组 id 集; 空 = 全部分组（步骤4 channel_scope 过滤用） */
    private final Set<Long> channelScope;
    /** QPS 限频值 */
    private final int rateLimit;
    /** 签名算法: hmac-sm3(默认) / hmac-sha256(配置回退) */
    private final String algorithm;

    private OpenAppAuth(Long appId, String appKey, String appSecret,
            Set<String> allowedApis, Set<Long> channelScope, int rateLimit, String algorithm) {
        this.appId = appId;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.allowedApis = Collections.unmodifiableSet(allowedApis);
        this.channelScope = Collections.unmodifiableSet(channelScope);
        this.rateLimit = rateLimit;
        this.algorithm = algorithm;
    }

    /** 从实体构建：解析逗号分隔的 allowed_apis/channel_scope, 忽略空白与非法数字段 */
    public static OpenAppAuth from(OpenAppEntity e, String plainSecret, String algorithm) {
        Set<String> apis = splitNonBlank(e.getAllowedApis());
        Set<Long> scopes = Arrays.stream(orEmpty(e.getChannelScope()).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(OpenAppAuth::toLongOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        int rate = e.getRateLimit() == null || e.getRateLimit() <= 0 ? DEFAULT_RATE_LIMIT : e.getRateLimit();
        return new OpenAppAuth(e.getId(), e.getAppKey(), plainSecret, apis, scopes, rate, algorithm);
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 是否放行指定路径（白名单空 = 仅只读接口的判定在过滤器, 此处只判显式白名单） */
    public boolean allows(String path) {
        return allowedApis.contains(path);
    }

    private static Set<String> splitNonBlank(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
