package com.yujian.vision.gb28181.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MANSRTSP 回放控制报文构造（GB/T 28181-2016 附录, docs/07 §3） */
class ManScdpCodecMansrtspTest {

    private static final String CRLF = "\r\n";

    @Test
    @DisplayName("PAUSE: 生成 <Pause/> 且含 Version/Seq")
    void pause() {
        String xml = ManScdpCodec.buildMansrtspControl(42L, "PAUSE", null);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + CRLF
                + "<Control>" + CRLF
                + "<Cmd>MANSRTSP</Cmd>" + CRLF
                + "<MANSRTSP>" + CRLF
                + "<Version>1.0</Version>" + CRLF
                + "<Seq>42</Seq>" + CRLF
                + "<Pause/>" + CRLF
                + "</MANSRTSP>" + CRLF
                + "</Control>" + CRLF, xml);
    }

    @Test
    @DisplayName("RESUME: 生成 <Resume/>")
    void resume() {
        String xml = ManScdpCodec.buildMansrtspControl(7L, "RESUME", null);
        assertTrue(xml.contains("<Resume/>" + CRLF));
        assertTrue(xml.contains("<Seq>7</Seq>"));
        assertFalse(xml.contains("<Scale>"));
    }

    @Test
    @DisplayName("SCALE: toPlainString 输出, 值与入参一致")
    void scale() {
        String xml = ManScdpCodec.buildMansrtspControl(9L, "SCALE", new java.math.BigDecimal("0.25"));
        assertTrue(xml.contains("<Scale>0.25</Scale>"));
        assertTrue(xml.contains("<Seq>9</Seq>"));

        String x2 = ManScdpCodec.buildMansrtspControl(10L, "SCALE", new java.math.BigDecimal("4"));
        assertTrue(x2.contains("<Scale>4</Scale>"));
    }

    @Test
    @DisplayName("PAUSE/RESUME 忽略 scale; SCALE 为空时兜底 1")
    void scaleDefaults() {
        assertFalse(ManScdpCodec.buildMansrtspControl(1L, "PAUSE", new java.math.BigDecimal("2")).contains("Scale"));
        assertEquals("<Scale>1</Scale>",
                ManScdpCodec.buildMansrtspControl(2L, "SCALE", null).lines()
                        .filter(l -> l.startsWith("<Scale>")).findFirst().orElse(""));
    }

    @Test
    @DisplayName("报文以 CRLF 结尾且根节点为 Control")
    void shape() {
        String xml = ManScdpCodec.buildMansrtspControl(1L, "PAUSE", null);
        assertTrue(xml.endsWith("</Control>" + CRLF));
        assertTrue(xml.contains("<Control>" + CRLF + "<Cmd>MANSRTSP</Cmd>"));
    }
}
