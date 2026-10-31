package com.yujian.vision.sip.transport.jain;

import gov.nist.javax.sip.header.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * contentOf 报文体解码策略单测。
 * 策略: charset 声明优先, 未声明时 UTF-8 严格校验(REPORT), 失败回退 GBK; 歧义字节按 UTF-8。
 * 直接用 NIST 实现类构造 Content-Type, 不起 SIP 栈。
 */
class JainSipContentDecodeTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static String decode(byte[] bytes, ContentType ct) {
        return JainSipTransportProvider.contentOf(bytes, ct);
    }

    @Test
    @DisplayName("null 报文体 → null")
    void nullContentReturnsNull() {
        assertNull(JainSipTransportProvider.contentOf(null, null));
    }

    @Test
    @DisplayName("非 byte[] → toString 直通")
    void nonBytesPassthrough() {
        assertEquals("MANSCDP+xml", JainSipTransportProvider.contentOf("MANSCDP+xml", null));
    }

    @Test
    @DisplayName("无声明 + 纯 ASCII → 原样")
    void asciiWithoutDeclaration() {
        assertEquals("<a/>", decode("<a/>".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    @DisplayName("无声明 + 合法 UTF-8 中文 → UTF-8")
    void validUtf8WithoutDeclaration() {
        // 中 = E4 B8 AD, 合法 UTF-8, 严格校验通过
        assertEquals("中", decode(new byte[]{(byte) 0xE4, (byte) 0xB8, (byte) 0xAD}, null));
    }

    @Test
    @DisplayName("无声明 + GBK 字节(非法 UTF-8) → GBK 回退")
    void gbkFallbackWithoutDeclaration_CTnull() {
        // 中 GBK = D6 D0; D6 为 UTF-8 双字节前导但 D0 非续字节 → 严格校验失败 → 回退 GBK
        byte[] bytes = "中".getBytes(GBK);
        assertInvalidUtf8(bytes);
        assertEquals("中", decode(bytes, null));
    }

    @Test
    @DisplayName("声明 charset=gb2312 + GBK 扩展字 → 按超集 GBK 解码")
    void declaredGb2312DecodedAsGbkSuperset() {
        // 镕(U+9555) 在 GBK 不在 GB2312; 设备声明 gb2312 实发 GBK 是常态, 超集解码无损
        String raw = "镕接";
        byte[] bytes = raw.getBytes(GBK);
        assertInvalidUtf8(bytes);
        ContentType ct = new ContentType("Application", "MANSCDP+xml");
        try {
            ct.setParameter("charset", "gb2312");
        } catch (Exception e) {
            fail("setParameter failed");
        }
        assertEquals(raw, decode(bytes, ct));
    }

    @Test
    @DisplayName("声明 charset=UTF-8 → 按声明解码")
    void declaredUtf8() {
        ContentType ct = new ContentType("Application", "MANSCDP+xml");
        try {
            ct.setParameter("charset", "UTF-8");
        } catch (Exception e) {
            fail("setParameter failed");
        }
        assertEquals("中", decode("中".getBytes(StandardCharsets.UTF_8), ct));
    }

    @Test
    @DisplayName("声明带引号 charset=\"gb2312\" → 去引号生效")
    void quotedCharsetParam() {
        ContentType ct = new ContentType("Application", "MANSCDP+xml");
        try {
            ct.setParameter("charset", "\"gb2312\"");
        } catch (Exception e) {
            fail("setParameter failed");
        }
        assertEquals("中", decode("中".getBytes(GBK), ct));
    }

    @Test
    @DisplayName("声明不支持的 charset → 回退自动探测")
    void unsupportedDeclaredCharsetFallsBackToProbe() {
        ContentType ct = new ContentType("Application", "MANSCDP+xml");
        try {
            ct.setParameter("charset", "x-unknown-charset");
        } catch (Exception e) {
            fail("setParameter failed");
        }
        assertEquals("中", decode("中".getBytes(StandardCharsets.UTF_8), ct));
    }

    @Test
    @DisplayName("歧义字节(同时合法 UTF-8 与 GBK) → UTF-8 优先")
    void ambiguousBytesPreferUtf8() {
        // C2 A0: UTF-8 = U+00A0 不间断空格, 也是合法 GBK 汉字码位; 策略固定 UTF-8 优先
        assertEquals(" ", decode(new byte[]{(byte) 0xC2, (byte) 0xA0}, null));
    }

    // ------------------------------------------------ 报文体 XML encoding 声明（M5-B2 联调缺陷）

    @Test
    @DisplayName("报文体声明 GB2312 + 歧义字节中文 → 按声明解(通道名「平台」乱码的成因)")
    void bodyDeclaredGb2312WinsOverAmbiguousUtf8() {
        // 「平台」GBK = C6 BD CC A8, 恰好也是合法 UTF-8 序列(→ U+01BD U+0328 = ƽ̨)。
        // 旧策略查不到 Content-Type charset 就做字节探测, 严格校验通过便不回退 → 解成 ƽ̨。
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n<Notify><CmdType>Catalog</CmdType>"
                + "<DeviceID>34020000001320000001</DeviceID><Name>平台</Name></Notify>";
        byte[] bytes = xml.getBytes(GBK);
        assertValidUtf8(bytes);
        assertEquals(xml, decode(bytes, null));
    }

    @Test
    @DisplayName("报文体声明 GB2312 + 单引号 → 同样按声明解")
    void bodyDeclaredGb2312SingleQuote() {
        String xml = "<?xml version='1.0' encoding='GB2312'?><a>平台</a>";
        assertEquals(xml, decode(xml.getBytes(GBK), null));
    }

    @Test
    @DisplayName("报文体声明 UTF-8 → 按声明解码")
    void bodyDeclaredUtf8() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>中文</a>";
        assertEquals(xml, decode(xml.getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    @DisplayName("SIP Content-Type charset 优先于报文体自述声明")
    void contentTypeCharsetWinsOverBodyDeclaration() {
        ContentType ct = new ContentType("Application", "MANSCDP+xml");
        try {
            ct.setParameter("charset", "UTF-8");
        } catch (Exception e) {
            fail("setParameter failed");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?><a>中文</a>";
        assertEquals(xml, decode(xml.getBytes(StandardCharsets.UTF_8), ct));
    }

    @Test
    @DisplayName("<?xml 出现在正文而非文档开头 → 不认声明, 仍走字节探测")
    void xmlDeclarationAwayFromStartIgnored() {
        // 「中」GBK = D6 D0 非法 UTF-8 → 回退 GBK, 证明声明未被采纳(采纳则会按 GBK 解也算对,
        // 故本用例的价值在反向: 若把正文里的 <?xml 也当声明, 遇到 unknown 名称时会误判)
        String raw = "<Query><CmdType>Catalog</CmdType><Note><?xml encoding=\"x-unknown\"?></Note>中</Query>";
        assertEquals(raw, decode(raw.getBytes(GBK), null));
    }

    @Test
    @DisplayName("报文体声明未知 charset → 忽略声明, 回退字节探测")
    void bodyDeclaredUnknownCharsetIgnored() {
        String xml = "<?xml version=\"1.0\" encoding=\"x-unknown\"?><a>中</a>";
        assertEquals(xml, decode(xml.getBytes(StandardCharsets.UTF_8), null));
    }

    private static void assertValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            fail("前置条件不成立: 字节竟非法 UTF-8, 歧义字节场景未被覆盖");
        }
    }

    private static void assertInvalidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException expected) {
            return;
        }
        fail("前置条件不成立: 字节竟为合法 UTF-8, GBK 回退分支未被覆盖");
    }
}
