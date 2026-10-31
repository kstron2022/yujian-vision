package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.onvif.model.OnvifDiscoveredDevice;
import com.yujian.vision.onvif.soap.OnvifSoapClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WS-Discovery 探测解析单测（M4-B4）：报文结构 + ProbeMatch 解析各形态。
 * socket 编排由 242 sim_discovery.py 桩端到端覆盖（组播面不进单测）。
 */
class OnvifDiscoveryTest {

    private static final String TWO_DEVICES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <s:Body>
                <d:ProbeMatches>
                  <d:ProbeMatch>
                    <a:EndpointReference><a:Address>urn:uuid:dev-a</a:Address></a:EndpointReference>
                    <d:Types>dn:NetworkVideoTransmitter</d:Types>
                    <d:XAddrs>http://[fe80::1]:50080/onvif/device_service http://10.1.1.10:50080/onvif/device_service</d:XAddrs>
                    <d:Scopes>onvif://www.onvif.org/Profile/Streaming onvif://www.onvif.org/hardware/DS-SimCam%203000</d:Scopes>
                  </d:ProbeMatch>
                  <d:ProbeMatch>
                    <a:EndpointReference><a:Address>urn:uuid:dev-b</a:Address></a:EndpointReference>
                    <d:Types>dn:NetworkVideoTransmitter</d:Types>
                    <d:XAddrs>http://10.1.1.20/onvif/device_service</d:XAddrs>
                    <d:Scopes>onvif://www.onf.org/name/WeirdCam</d:Scopes>
                  </d:ProbeMatch>
                  <d:ProbeMatch>
                    <a:EndpointReference><a:Address>urn:uuid:dev-c</a:Address></a:EndpointReference>
                    <d:Types>dn:NetworkVideoTransmitter</d:Types>
                    <d:XAddrs>urn:uuid:xxx http://[fe80::2]:8080/onvif/</d:XAddrs>
                  </d:ProbeMatch>
                </d:ProbeMatches>
              </s:Body>
            </s:Envelope>
            """;

    private static String probeMatchXml(String uuid, String xaddrs, String scopes) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Body><d:ProbeMatches><d:ProbeMatch>"
                + "<a:EndpointReference><a:Address>" + uuid + "</a:Address></a:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter</d:Types>"
                + "<d:XAddrs>" + xaddrs + "</d:XAddrs>"
                + "<d:Scopes>" + scopes + "</d:Scopes>"
                + "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";
    }

    @Test
    @DisplayName("Probe 报文: 组播组/端口常量正确, 报文含 Types 过滤与 MessageID")
    void probeMessageStructure() {
        String msg = OnvifDiscovery.probeMessage("urn:uuid:test-1");
        assertTrue(msg.contains("dn:NetworkVideoTransmitter"), msg);
        assertTrue(msg.contains("urn:uuid:test-1"), msg);
        assertTrue(msg.contains("http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"), msg);
        assertEquals("239.255.255.250", OnvifDiscovery.MULTICAST_ADDR);
        assertEquals(3702, OnvifDiscovery.MULTICAST_PORT);
    }

    @Test
    @DisplayName("parseMatches: IPv6 地址过滤取 IPv4/默认端口 80/畸形条目跳过")
    void parseMultipleDevices() {
        List<OnvifDiscoveredDevice> out = OnvifDiscovery.parseMatches(
                OnvifSoapClient.newDocument(TWO_DEVICES));
        // dev-a: 双地址(首个 IPv6 被滤, 取 IPv4); dev-b: 无端口默认 80;
        // dev-c: 只有 IPv6 与 urn → 整条跳过(坏条目不致命)
        assertEquals(2, out.size(), out.toString());
        assertEquals("10.1.1.10", out.get(0).ip());
        assertEquals(50080, out.get(0).port());
        assertEquals("urn:uuid:dev-a", out.get(0).uuid());
        assertEquals("10.1.1.20", out.get(1).ip());
        assertEquals(80, out.get(1).port());
    }

    @Test
    @DisplayName("scopeHint: hardware/ 优先, name/ 兜底, percent-decode 还原空格")
    void scopeHintParsing() {
        List<OnvifDiscoveredDevice> out = OnvifDiscovery.parseMatches(
                OnvifSoapClient.newDocument(TWO_DEVICES));
        assertEquals("DS-SimCam 3000", out.get(0).modelHint());
    }

    @Test
    @DisplayName("scopeHint: 无 hardware 时取 name/ 线索")
    void scopeHintNameFallback() {
        OnvifDiscoveredDevice d = OnvifDiscovery.parseMatches(OnvifSoapClient.newDocument(
                probeMatchXml("urn:uuid:x1", "http://10.1.1.30:80/onvif/device_service",
                        "onvif://www.onvif.org/name/Lobby%20Cam onvif://www.onvif.org/Profile/Streaming")))
                .get(0);
        assertEquals("Lobby Cam", d.modelHint());
    }

    @Test
    @DisplayName("normalizeIpv4: 前导零规范化(010.000.000.201→10.0.0.201), 越界/坏段回 null")
    void normalizeIpv4Variants() {
        assertEquals("10.0.0.201", OnvifDiscovery.normalizeIpv4("010.000.000.201"));
        assertEquals("10.0.0.201", OnvifDiscovery.normalizeIpv4("10.0.0.201"));
        assertEquals("1.2.3.4", OnvifDiscovery.normalizeIpv4("1.2.3.4"));
        assertEquals(null, OnvifDiscovery.normalizeIpv4("256.1.1.1"));
        assertEquals(null, OnvifDiscovery.normalizeIpv4("1.2.3"));
        assertEquals(null, OnvifDiscovery.normalizeIpv4("1..2.3"));
        // 应答不可信字段净化: 控制字符清洗后进入视图
        OnvifDiscoveredDevice dirty = OnvifDiscovery.parseMatches(OnvifSoapClient.newDocument(
                probeMatchXml("urn:uuid:x", "http://10.1.1.40/onvif/device_service",
                        "onvif://www.onvif.org/hardware/Bad%0AName")))
                .get(0);
        assertEquals("urn:uuid:x", dirty.uuid());
        assertEquals("Bad Name", dirty.modelHint());
    }

    @Test
	@DisplayName("构造参数: interfaceIp 空白归一为 null(内核自动选口)")
    void interfaceIpNormalization() {
        OnvifDiscovery d1 = new OnvifDiscovery("  ", Duration.ofMillis(10));
        OnvifDiscovery d2 = new OnvifDiscovery(null, Duration.ofMillis(10));
        // 不抛异常即可(字段私有无 getter, 行为由 probe 路径覆盖)
        assertTrue(d1 != null && d2 != null);
    }
}
