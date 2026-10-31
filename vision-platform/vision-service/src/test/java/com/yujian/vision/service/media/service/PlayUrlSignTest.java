package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.yujian.vision.common.security.AesCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 播放 URL 签名契约（docs/03 §1.2: md5(streamId+expire+secret)） */
class PlayUrlSignTest {

    private final PlayUrlService svc = new PlayUrlService(null, new AesCipher("unit-test-key"), "", "");

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("正确签名+未过期 → 通过")
    void validSign() {
        String expire = String.valueOf(System.currentTimeMillis() / 1000 + 600);
        assertTrue(svc.verify("gb_play_c1", md5("gb_play_c1" + expire + "secret"), expire, "secret"));
    }

    @Test
    @DisplayName("过期 → 拒绝")
    void expired() {
        String expire = String.valueOf(System.currentTimeMillis() / 1000 - 10);
        assertFalse(svc.verify("gb_play_c1", md5("gb_play_c1" + expire + "secret"), expire, "secret"));
    }

    @Test
    @DisplayName("签名篡改 → 拒绝")
    void tampered() {
        String expire = String.valueOf(System.currentTimeMillis() / 1000 + 600);
        assertFalse(svc.verify("gb_play_c1", "deadbeef", expire, "secret"));
    }

    @Test
    @DisplayName("密钥不符 → 拒绝")
    void wrongSecret() {
        String expire = String.valueOf(System.currentTimeMillis() / 1000 + 600);
        assertFalse(svc.verify("gb_play_c1", md5("gb_play_c1" + expire + "other"), expire, "secret"));
    }

    @Test
    @DisplayName("垃圾 expire → 拒绝而非 500 (F7)")
    void garbageExpire() {
        String sign = md5("gb_play_c1" + "abc" + "secret");
        assertFalse(svc.verify("gb_play_c1", sign, "abc", "secret"));
    }

    @Test
    @DisplayName("空参数 → 拒绝")
    void nullArgs() {
        assertFalse(svc.verify(null, "x", "1", "s"));
        assertFalse(svc.verify("s", null, "1", "s"));
        assertFalse(svc.verify("s", "x", null, "s"));
        assertFalse(svc.verify("s", "x", "1", null));
    }

    @Test
    @DisplayName("B5 buildUrls: node IP assembles five formats; rtc targets ZLM SDP exchange API")
    void buildUrlsDefaultBase() {
        var mapper = org.mockito.Mockito.mock(com.yujian.vision.service.media.mapper.GbMediaServerMapper.class);
        var node = new com.yujian.vision.service.media.entity.GbMediaServerEntity();
        node.setServerId("vision-zlm");
        node.setPublicIp("10.0.0.9");
        node.setHttpPort(8081);
        node.setSignSecret(new com.yujian.vision.common.security.AesCipher("unit-test-key").encrypt("secret"));
        org.mockito.Mockito.when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(node);
        var urlSvc = new PlayUrlService(mapper, new com.yujian.vision.common.security.AesCipher("unit-test-key"), "", "");
        var urls = urlSvc.buildUrls("vision-zlm", "gb_play_x", 600);
        assertTrue(urls.get("flv").startsWith("http://10.0.0.9:8081/rtp/gb_play_x.live.flv?sign="));
        assertTrue(urls.get("wsFlv").startsWith("ws://10.0.0.9:8081/rtp/gb_play_x.live.flv?sign="));
        assertTrue(urls.get("hls").startsWith("http://10.0.0.9:8081/rtp/gb_play_x/hls.m3u8?sign="));
        assertTrue(urls.get("ts").startsWith("http://10.0.0.9:8081/rtp/gb_play_x.live.ts?sign="));
        assertTrue(urls.get("rtc").startsWith("http://10.0.0.9:8081/index/api/webrtc?app=rtp&stream=gb_play_x&type=play&sign="));
    }

    @Test
    @DisplayName("B5 buildUrls: public-base overrides all bases with http to ws derivation")
    void buildUrlsPublicBaseOverride() {
        var mapper = org.mockito.Mockito.mock(com.yujian.vision.service.media.mapper.GbMediaServerMapper.class);
        var node = new com.yujian.vision.service.media.entity.GbMediaServerEntity();
        node.setServerId("vision-zlm");
        node.setPublicIp("10.0.0.9");
        node.setHttpPort(8081);
        node.setSignSecret(new com.yujian.vision.common.security.AesCipher("unit-test-key").encrypt("secret"));
        org.mockito.Mockito.when(mapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(node);
        var urlSvc = new PlayUrlService(mapper, new com.yujian.vision.common.security.AesCipher("unit-test-key"), "https://media.example.com", "");
        var urls = urlSvc.buildUrls("vision-zlm", "gb_play_x", 600);
        assertTrue(urls.get("flv").startsWith("https://media.example.com/rtp/"));
        assertTrue(urls.get("wsFlv").startsWith("wss://media.example.com/rtp/"));
        assertTrue(urls.get("rtc").startsWith("https://media.example.com/index/api/webrtc?"));
    }
}
