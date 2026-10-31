package com.yujian.vision.service.cascade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.UnsupportedCharsetException;

import org.junit.jupiter.api.Test;

/** 级联出站编码适配单测（LiveGBS GB 系码制兼容, 2026-09-25 评审补充） */
class CascadeOutboundCodecTest {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Notify>";

    @Test
    void defaultsToGbkWithDeclarationRewrite() {
        CascadeOutboundCodec c = new CascadeOutboundCodec(null); // 不配置 = 默认 GBK
        assertTrue(c.tuned());
        assertEquals("GBK", c.charset());
        assertEquals("<?xml version=\"1.0\" encoding=\"GBK\"?><Notify>", c.body(XML));
    }

    @Test
    void keepsUtf8WhenConfigured() {
        CascadeOutboundCodec c = new CascadeOutboundCodec("UTF-8");
        assertFalse(c.tuned());
        assertEquals(XML, c.body(XML));
    }

    @Test
    void rewritesDeclarationForGb2312() {
        CascadeOutboundCodec c = new CascadeOutboundCodec("GB2312");
        assertTrue(c.tuned());
        assertEquals("<?xml version=\"1.0\" encoding=\"GB2312\"?><Notify>", c.body(XML));
        assertEquals("GB2312", c.charset());
    }

    @Test
    void tolerantToMissingDeclaration() {
        CascadeOutboundCodec c = new CascadeOutboundCodec("GBK");
        assertEquals("<Notify/>", c.body("<Notify/>"));
    }

    @Test
    void rejectsUnsupportedCharsetAtStartup() {
        // fail-fast（评审 P2-4）: 非法编码名启动期暴露, 不允许运行期每条报文炸
        assertThrows(UnsupportedCharsetException.class, () -> new CascadeOutboundCodec("GB-2312-X"));
    }
}
