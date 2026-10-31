package com.yujian.vision.onvif.soap;

import com.yujian.vision.onvif.model.OnvifCallException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 轻量 SOAP 客户端（docs/13 §3.1）：JDK HttpClient POST 信封 + 命名空间感知的 Fault 提取。
 * 单例 HttpClient 复用连接池；文档工厂禁外部实体（XXE 防护, docs/10 基线）。
 */
public final class OnvifSoapClient {

    private static final String SOAP_ENVELOPE_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String SOAP_FAULT_LOCAL = "Fault";
    private static final String SOAP_REASON_LOCAL = "Reason";
    private static final String SOAP_TEXT_LOCAL = "Text";

    private final HttpClient http;
    private final Duration timeout;

    public OnvifSoapClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** POST 信封并返回 200 应答体；非 200/解析失败抛 {@link OnvifCallException}（含 Fault reason） */
    public String post(String url, String envelope) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OnvifCallException("连接设备失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OnvifCallException("请求被中断", e);
        }
        if (resp.statusCode() != 200) {
            throw new OnvifCallException("设备应答 HTTP " + resp.statusCode() + ": " + faultText(resp.body()));
        }
        return resp.body();
    }

    /**
     * POST 并要求业务成功：HTTP 200 但应答体内含 SOAP Fault 时同样抛 {@link OnvifCallException}。
     * PTZ/配置类操作无有价值的应答体, 部分厂商对失败操作回 200+Fault, 静默吞掉会造成"假成功"。
     */
    public String postStrict(String url, String envelope) {
        String body = post(url, envelope);
        String fault = faultText(body);
        if (!fault.isEmpty()) {
            throw new OnvifCallException("设备拒绝操作: " + fault);
        }
        return body;
    }

    /** 从应答体提取 SOAP Fault 文案（无 Fault 返回空串） */
    public static String faultText(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        try {
            Document doc = newDocument(body);
            // Fault 位于 env:Body 之下(cam1 实证: 直接查 Envelope 子级会漏, 退化成截断原文,
            // 把相机已明确给出的 "Optional Action Not Implemented" 埋进 XML 里)
            Element root = doc.getDocumentElement();
            Element fault = null;
            Element envBody = firstByNs(root, SOAP_ENVELOPE_NS, "Body");
            if (envBody != null) {
                fault = firstByNs(envBody, SOAP_ENVELOPE_NS, SOAP_FAULT_LOCAL);
            }
            if (fault == null) {
                if (envBody != null || (root != null
                        && SOAP_ENVELOPE_NS.equals(root.getNamespaceURI())
                        && "Envelope".equals(root.getLocalName()))) {
                    // 规范 SOAP 应答但无 Fault = 操作成功（2026-09-25 球机 PTZ 实证: 200 成功应答
                    // 曾被当"任意非 SOAP 体"截断报文头, postStrict 误报"设备拒绝操作"）, 返回空串
                    return "";
                }
                // 设备/内网服务可能返回任意非 SOAP 体: 清洗后截断, 防换行/控制字符伪造日志与审计
                return sanitize(body.length() > 200 ? body.substring(0, 200) : body);
            }
            Element reason = firstByNs(fault, SOAP_ENVELOPE_NS, SOAP_REASON_LOCAL);
            if (reason != null) {
                Element text = firstByNs(reason, SOAP_ENVELOPE_NS, SOAP_TEXT_LOCAL);
                if (text != null && text.getTextContent() != null) {
                    return sanitize(text.getTextContent().trim());
                }
            }
            // SOAP 1.1 兼容: faultstring
            Element s11 = childByLocal(fault, "faultstring");
            if (s11 != null) {
                return sanitize(s11.getTextContent().trim());
            }
            return "unknown fault";
        } catch (RuntimeException e) {
            return "unparseable response";
        }
    }

    /** 换行/控制字符清洗（设备侧受控文本进日志/审计前的脱敏, docs/13 §2.1） */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 13 || c == 10 || c == 9) {  // CR/LF/TAB 压空格(整数比较, 规避源码转义)
                sb.append(' ');
            } else if (c < 0x20 || c == 0x7F) {
                // 其余控制字符丢弃
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** XXE 防护的文档工厂（禁 DTD/外部实体） */
    public static Document newDocument(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            return f.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception e) {
            throw new OnvifCallException("应答 XML 解析失败: " + e.getMessage(), e);
        }
    }

    /** 命名空间感知查找：parent 直接子元素中第一个 ns+localName 匹配者 */
    public static Element firstByNs(Element parent, String ns, String local) {
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el
                    && local.equals(el.getLocalName())
                    && ns.equals(el.getNamespaceURI())) {
                return el;
            }
        }
        return null;
    }

    /** 忽略命名空间按 localName 查找（厂商容错兜底） */
    public static Element childByLocal(Element parent, String local) {
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el && local.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    
}
