package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yujian.vision.onvif.soap.OnvifSoapClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** faultText 回归(cam1 实证): Fault 在 env:Body 之下, 必须提取 Reason 文本而非截断原文 */
class OnvifSoapClientFaultTest {

    @Test
    @DisplayName("SOAP 1.2 Fault 在 Body 层(env 前缀, 海康真实形态) → 提取 Reason 文本")
    void faultUnderBody() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:ter=\"http://www.onvif.org/ver10/error\">"
                + "<env:Body><env:Fault><env:Code><env:Value>env:Receiver</env:Value>"
                + "<env:Subcode><env:Value>ter:ActionNotSupported</env:Value></env:Subcode></env:Code>"
                + "<env:Reason><env:Text xml:lang=\"en\">Optional Action Not Implemented</env:Text></env:Reason>"
                + "</env:Fault></env:Body></env:Envelope>";
        assertEquals("Optional Action Not Implemented", OnvifSoapClient.faultText(body));
    }

    @Test
    @DisplayName("非 SOAP 体 → 清洗后截断, 不抛异常")
    void nonSoapBody() {
        String out = OnvifSoapClient.faultText("<html>bad gateway\r\nsecond line</html>");
        assertEquals("<html>bad gateway  second line</html>", out);  // CR 与 LF 各替换一个空格
    }

    @Test
    @DisplayName("SOAP 成功应答(无 Fault, 球机 PTZ 200 实证形态) → 空串, 不得当非 SOAP 体截断")
    void successEnvelope() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:soapenc=\"http://www.w3.org/2003/05/soap-encoding\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<env:Body><tptz:ContinuousMoveResponse"
                + " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"/></env:Body>"
                + "</env:Envelope>";
        assertEquals("", OnvifSoapClient.faultText(body));
    }
}
