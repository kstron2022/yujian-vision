package com.yujian.vision.service.device.rtsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 拉流代理静态支撑单测（M5-B1）: URL/transport 校验归一 + 批量导入行解析（两种行格式/注释/切分）。
 */
class RtspSourceSupportTest {

    @Test
    @DisplayName("合法 rtsp 地址(含账密/端口/路径)归一返回 trim 值")
    void validUrlOk() {
        String url = "  rtsp://admin:pass@192.168.1.64:554/Streaming/Channels/101  ";
        assertEquals(url.trim(), RtspSourceSupport.validateSourceUrl(url));
    }

    @Test
    @DisplayName("非 rtsp scheme / 缺 host / 空格 / 超长 / 密文形态 → 400")
    void invalidUrlsRejected() {
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("http://10.0.0.1/live"));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp:///nohost"));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp://10.0.0.1/a b"));
        String longUrl = "rtsp://10.0.0.1/" + "a".repeat(RtspSourceSupport.URL_MAX_LEN);
        assertThrows(BizException.class, () -> RtspSourceSupport.validateSourceUrl(longUrl));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("enc:v1:Abcd"));
        assertThrows(BizException.class, () -> RtspSourceSupport.validateSourceUrl("  "));
        BizException e = assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl(null));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("transport: 空→tcp, 大写归一小写, 非法值拒绝")
    void transportNormalize() {
        assertEquals("tcp", RtspSourceSupport.validateTransport(null));
        assertEquals("tcp", RtspSourceSupport.validateTransport(""));
        assertEquals("udp", RtspSourceSupport.validateTransport(" UDP "));
        assertThrows(BizException.class, () -> RtspSourceSupport.validateTransport("http"));
    }

    @Test
    @DisplayName("行解析: 纯 URL 自动命名 / 名称,URL / 注释与空行跳过 / 首个逗号切分")
    void parseLines() {
        String text = """
                # 注释行
                rtsp://10.0.0.1/a

                广场东侧,rtsp://10.0.0.2/b,c=1
                ,rtsp://10.0.0.3/d
                """;
        List<RtspSourceSupport.ParsedLine> lines = RtspSourceSupport.parseLines(text);
        assertEquals(3, lines.size());
        assertEquals(2, lines.get(0).lineNo(), "物理行号(跳过注释前已计)");
        assertNull(lines.get(0).name());
        assertEquals("rtsp://10.0.0.1/a", lines.get(0).url());
        assertEquals("广场东侧", lines.get(1).name());
        assertEquals("rtsp://10.0.0.2/b,c=1", lines.get(1).url(), "URL 段含逗号不受影响");
        assertNull(lines.get(2).name(), "空名称行 URL 原样保留(含前导逗号, 由 URL 校验拒绝进 failed)");
        assertEquals(",rtsp://10.0.0.3/d", lines.get(2).url());
    }

    @Test
    @DisplayName("行解析: 空文本返回空清单; 超过单次上限截断")
    void parseLinesEdge() {
        assertEquals(0, RtspSourceSupport.parseLines(null).size());
        assertEquals(0, RtspSourceSupport.parseLines("  \n # only comment").size());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < RtspSourceSupport.BATCH_MAX_LINES + 10; i++) {
            many.append("rtsp://10.0.0.").append(i).append("/a\n");
        }
        assertEquals(RtspSourceSupport.BATCH_MAX_LINES,
                RtspSourceSupport.parseLines(many.toString()).size());
    }

    @Test
    @DisplayName("IP 字面量黑名单: 回环/链路本地/组播拒绝; 域名与普通内网 IP 放行")
    void dangerousHostRejected() {
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp://admin:pass@127.0.0.1:554/live"));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp://169.254.169.254/latest"));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp://239.1.1.1/live"));
        assertThrows(BizException.class,
                () -> RtspSourceSupport.validateSourceUrl("rtsp://0.0.0.0/live"));
        assertEquals("rtsp://cam.local:554/live",
                RtspSourceSupport.validateSourceUrl("rtsp://cam.local:554/live"), "域名放行");
        assertEquals("rtsp://10.0.0.242:554/live",
                RtspSourceSupport.validateSourceUrl("rtsp://10.0.0.242:554/live"), "普通内网 IP 放行");
    }
}
