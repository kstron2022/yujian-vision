package com.yujian.vision.service.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.common.security.SmCipher;
import com.yujian.vision.service.openapi.domain.OpenAppAuth;
import com.yujian.vision.service.openapi.entity.OpenAppEntity;
import com.yujian.vision.service.openapi.mapper.OpenAppMapper;
import com.yujian.vision.service.openapi.service.OpenAppService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 开放接口应用管理单元测试（docs/15 步骤2）：纯 Mockito + 真实 SmCipher（密文形制断言）,
 * 不起 Spring 上下文/数据库。
 */
@ExtendWith(MockitoExtension.class)
class OpenAppServiceTest {

    private static final String KEY = "unit-test-sm4-key-0123456789abcdef";
    private static final SmCipher CIPHER = new SmCipher(KEY);

    @Mock
    private OpenAppMapper appMapper;

    private OpenAppService service() {
        return new OpenAppService(appMapper, CIPHER);
    }

    private OpenAppEntity entity(Long id, String key, Integer status, LocalDateTime expire) {
        OpenAppEntity e = new OpenAppEntity();
        e.setId(id);
        e.setAppKey(key);
        e.setAppSecret(CIPHER.encrypt("plain-secret-48"));
        e.setAppName("app" + id);
        e.setAllowedApis("/openapi/v1/devices");
        e.setChannelScope("1,2");
        e.setRateLimit(20);
        e.setStatus(status);
        e.setExpireTime(expire);
        return e;
    }

    @Test
    @DisplayName("create: app_key=32位hex, secret=48位且SM4落库可解回, 明文仅返回一次")
    void createGeneratesCredential() {
        OpenAppService svc = service();
        when(appMapper.insert(any(OpenAppEntity.class))).thenReturn(1);
        OpenAppService.AppCredential c =
                svc.create("app-a", "/openapi/v1/devices,/openapi/v1/channels", "1,2", 5, null);

        ArgumentCaptor<OpenAppEntity> captor = ArgumentCaptor.forClass(OpenAppEntity.class);
        verify(appMapper).insert(captor.capture());
        OpenAppEntity saved = captor.getValue();
        assertTrue(saved.getAppKey().matches("[0-9a-f]{32}"));
        assertEquals(48, c.appSecret().length());
        assertTrue(saved.getAppSecret().startsWith("sm4$"));
        assertEquals(c.appSecret(), new SmCipher(KEY).decrypt(saved.getAppSecret()));
        assertEquals(Integer.valueOf(1), saved.getStatus());
        assertEquals(Integer.valueOf(5), saved.getRateLimit());
    }

    @Test
    @DisplayName("create: 空名称拒绝; CSV 归一化去空白/空段; rateLimit 空兜底 50")
    void createValidationAndNormalize() {
        OpenAppService svc = service();
        when(appMapper.insert(any(OpenAppEntity.class))).thenReturn(1);
        assertThrowsBadRequest(() -> svc.create(" ", "", "", null, null));
        svc.create("app", " /openapi/v1/devices , , /openapi/v1/groups ", "", null, null);
        ArgumentCaptor<OpenAppEntity> captor = ArgumentCaptor.forClass(OpenAppEntity.class);
        verify(appMapper).insert(captor.capture());
        assertEquals("/openapi/v1/devices,/openapi/v1/groups", captor.getValue().getAllowedApis());
        assertEquals("", captor.getValue().getChannelScope());
        assertEquals(Integer.valueOf(50), captor.getValue().getRateLimit());
    }

    @Test
    @DisplayName("page/get: app_secret 密文不出服务层")
    void pageAndGetMaskSecret() {
        OpenAppService svc = service();
        Page<OpenAppEntity> p = new Page<>(1, 10);
        p.setRecords(List.of(entity(1L, "k1", 1, null), entity(2L, "k2", 1, null)));
        // 无参 any() 走目标类型推断(P/T 取自 mapper 签名), 规避裸 Page.class/Wrapper.class 的 unchecked
        when(appMapper.selectPage(any(), any())).thenReturn(p);
        IPage<OpenAppEntity> out = svc.page(1, 10, null, null);
        assertTrue(out.getRecords().stream().allMatch(e -> e.getAppSecret() == null));

        when(appMapper.selectById(1L)).thenReturn(entity(1L, "k1", 1, null));
        assertNull(svc.get(1L).getAppSecret());
    }

    private void assertThrowsBadRequest(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expect BizException(BAD_REQUEST)");
        } catch (BizException e) {
            assertEquals(400, e.getErrorCode().getCode());
        }
    }

    @Test
    @DisplayName("resetSecret: 新明文与落库密文一致, 且与旧 secret 不同")
    void resetSecretRotates() {
        OpenAppService svc = service();
        when(appMapper.selectById(1L)).thenReturn(entity(1L, "k1", 1, null));
        when(appMapper.insert(any(OpenAppEntity.class))).thenReturn(1);
        String oldSecret = svc.create("app", "", "", null, null).appSecret();
        OpenAppService.AppCredential c = svc.resetSecret(1L);
        assertNotEquals(oldSecret, c.appSecret());
        ArgumentCaptor<OpenAppEntity> captor = ArgumentCaptor.forClass(OpenAppEntity.class);
        verify(appMapper).updateById(captor.capture());
        assertEquals(c.appSecret(), new SmCipher(KEY).decrypt(captor.getValue().getAppSecret()));
    }

    @Test
    @DisplayName("updateStatus: 非法值拒绝; get/delete: 不存在抛 NOT_FOUND")
    void statusAndNotFoundValidation() {
        OpenAppService svc = service();
        when(appMapper.selectById(1L)).thenReturn(entity(1L, "k1", 1, null)).thenReturn(null);
        assertThrowsBadRequest(() -> svc.updateStatus(1L, 2));
        assertThrowsNotFound(() -> svc.get(1L));
        assertThrowsNotFound(() -> svc.delete(9L));
    }

    @Test
    @DisplayName("loadAuth: 正常→解析白名单/范围/算法; 停用/过期/不存在→null")
    void loadAuthPaths() {
        OpenAppService svc = service();
        when(appMapper.selectOne(any()))
                .thenReturn(entity(7L, "k7", 1, null))
                .thenReturn(entity(7L, "k7", 0, null))
                .thenReturn(entity(7L, "k7", 1, LocalDateTime.now().minusMinutes(1)))
                .thenReturn(null);

        OpenAppAuth auth = svc.loadAuth("k7", "hmac-sm3");
        assertEquals(7L, auth.getAppId());
        assertEquals(List.of("/openapi/v1/devices"), List.copyOf(auth.getAllowedApis()));
        assertEquals(2, auth.getChannelScope().size());
        assertEquals(20, auth.getRateLimit());
        assertEquals("hmac-sm3", auth.getAlgorithm());
        assertEquals("plain-secret-48", auth.getAppSecret());

        assertNull(svc.loadAuth("k7", "hmac-sm3"));
        assertNull(svc.loadAuth("k7", "hmac-sm3"));
        assertNull(svc.loadAuth("missing", "hmac-sm3"));
    }

    @Test
    @DisplayName("loadAuth: rateLimit 空/非法兜底 50; channelScope 非法数字段忽略")
    void loadAuthToleratesDirtyData() {
        OpenAppService svc = service();
        OpenAppEntity e = entity(7L, "k7", 1, null);
        e.setRateLimit(0);
        e.setChannelScope("1,x,,3");
        e.setAllowedApis("");
        when(appMapper.selectOne(any())).thenReturn(e);
        OpenAppAuth auth = svc.loadAuth("k7", "hmac-sha256");
        assertEquals(50, auth.getRateLimit());
        assertTrue(auth.getChannelScope().containsAll(java.util.Set.of(1L, 3L)));
        assertTrue(auth.getAllowedApis().isEmpty());
    }

    private void assertThrowsNotFound(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expect BizException(NOT_FOUND)");
        } catch (BizException e) {
            assertEquals(404, e.getErrorCode().getCode());
        }
    }
}
