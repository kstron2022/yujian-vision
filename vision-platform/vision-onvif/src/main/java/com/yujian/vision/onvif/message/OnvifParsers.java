package com.yujian.vision.onvif.message;

import com.yujian.vision.onvif.OnvifTime;
import com.yujian.vision.onvif.model.OnvifDeviceInfo;
import com.yujian.vision.onvif.model.OnvifProfile;
import com.yujian.vision.onvif.model.OnvifVideoSource;
import com.yujian.vision.onvif.soap.OnvifSoapClient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * ONVIF 应答解析（命名空间感知 + localName 容错兜底，docs/13 §8 厂商差异）。
 * 全部静态纯函数，单测直接喂 XML fixture。
 */
public final class OnvifParsers {

    private OnvifParsers() {
    }

    /** GetDeviceInformationResponse → 设备信息（厂商字段缺失容错为空串） */
    public static OnvifDeviceInfo deviceInfo(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_DEVICE, "GetDeviceInformationResponse");
        // HTTP 200 + 非 GetDeviceInformationResponse 体(部分厂商认证失败回 200+Fault): 守卫 NPE
        if (resp == null) {
            return new OnvifDeviceInfo("", "", "", "", "");
        }
        return new OnvifDeviceInfo(
                text(resp, "Manufacturer"),
                text(resp, "Model"),
                text(resp, "FirmwareVersion"),
                text(resp, "SerialNumber"),
                text(resp, "HardwareId"));
    }

    /** GetVideoSourcesResponse → VideoSource 列表（token 属性；分辨率容错缺省 0） */
    public static List<OnvifVideoSource> videoSources(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_DEVICE, "GetVideoSourcesResponse");
        List<OnvifVideoSource> out = new ArrayList<>();
        if (resp == null) {
            return out;
        }
        for (Element vs : children(resp, OnvifMessages.NS_SCHEMA, "VideoSource")) {
            out.add(new OnvifVideoSource(vs.getAttribute("token"), List.of()));
        }
        return out;
    }

    /** GetProfilesResponse → Profile 列表（SourceToken 关联 VideoSource，PTZConfiguration 有则云台可用） */
    public static List<OnvifProfile> profiles(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_MEDIA, "GetProfilesResponse");
        List<OnvifProfile> out = new ArrayList<>();
        if (resp == null) {
            return out;
        }
        // 标准子元素为 tt:Profile; 海康 V5.5.x 固件(cam1 实证)写成 trt:Profiles(复数)——容错兜底
        List<Element> elems = children(resp, OnvifMessages.NS_SCHEMA, "Profile");
        if (elems.isEmpty()) {
            elems = children(resp, OnvifMessages.NS_MEDIA, "Profiles");
        }
        for (Element p : elems) {
            String token = p.getAttribute("token");
            String name = text(p, "Name");
            String sourceToken = "";
            Element vsc = first(p, OnvifMessages.NS_SCHEMA, "VideoSourceConfiguration");
            if (vsc != null) {
                Element st = first(vsc, OnvifMessages.NS_SCHEMA, "SourceToken");
                if (st != null) {
                    sourceToken = st.getTextContent().trim();
                }
            }
            int width = 0;
            int height = 0;
            String encoding = "";
            Element vec = first(p, OnvifMessages.NS_SCHEMA, "VideoEncoderConfiguration");
            if (vec != null) {
                Element enc = first(vec, OnvifMessages.NS_SCHEMA, "Encoding");
                encoding = enc != null && enc.getTextContent() != null ? enc.getTextContent().trim() : "";
                Element res = first(vec, OnvifMessages.NS_SCHEMA, "Resolution");
                if (res != null) {
                    width = intOf(first(res, OnvifMessages.NS_SCHEMA, "Width"));
                    height = intOf(first(res, OnvifMessages.NS_SCHEMA, "Height"));
                }
            }
            boolean ptz = first(p, OnvifMessages.NS_SCHEMA, "PTZConfiguration") != null;
            out.add(new OnvifProfile(token, name, sourceToken, width, height, encoding, ptz));
        }
        return out;
    }

    /**
     * GetStreamUriResponse / GetSnapshotUriResponse → 流地址（两者应答同为 tt:MediaUri/tt:Uri 形状）。
     * 设备未实现该可选操作时回空串（HTTP 200 + Fault 或缺元素）。
     */
    public static String mediaUri(Document doc) {
        // GetStreamUriResponse(trt) / GetSnapshotUriResponse(trt) 直接子级未命中时按后代兜底(与 first 同口径)
        Element resp = first(doc, OnvifMessages.NS_MEDIA, "GetStreamUriResponse");
        if (resp == null) {
            resp = first(doc, OnvifMessages.NS_MEDIA, "GetSnapshotUriResponse");
        }
        if (resp == null) {
            return "";
        }
        return text(resp, "Uri");
    }

    /** GetCapabilitiesResponse → PTZ 服务 XAddr（设备未实现/无 PTZ 服务回空串, 调用方回退 device_service） */
    public static String ptzXAddr(Document doc) {
        return capsXAddr(doc, "PTZ");
    }

    /** GetCapabilitiesResponse → Replay 服务 XAddr（M4-B3 回放取流; 未宣告回空串, 调用方回退 device_service） */
    public static String replayXAddr(Document doc) {
        return capsXAddr(doc, "Replay");
    }

    /** GetCapabilitiesResponse → Imaging 服务 XAddr（聚焦控制; 未宣告回空串, 调用方回退 device_service） */
    public static String imagingXAddr(Document doc) {
        return capsXAddr(doc, "Imaging");
    }

    /**
     * GetPresetsResponse → 预置位列表（2026-09-25 现场补齐）。
     * 条目形状 = &lt;tptz:Preset token=".."&gt;&lt;tt:Name&gt;..&lt;/tt:Name&gt;&lt;/tptz:Preset&gt;;
     * 无 token 条目跳过（goto/delete 无法引用）; Name 缺失回空串由展示层兜底。
     */
    public static List<com.yujian.vision.onvif.model.OnvifPreset> presets(Document doc) {
        List<com.yujian.vision.onvif.model.OnvifPreset> out = new ArrayList<>();
        Element resp = first(doc, OnvifMessages.NS_PTZ, "GetPresetsResponse");
        if (resp == null) {
            return out;
        }
        var nodes = resp.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element preset)
                    || !OnvifMessages.NS_PTZ.equals(preset.getNamespaceURI())
                    || !"Preset".equals(preset.getLocalName())) {
                continue;
            }
            String token = preset.getAttribute("token");
            if (token == null || token.isBlank()) {
                continue;
            }
            Element name = first(preset, OnvifMessages.NS_SCHEMA, "Name");
            out.add(new com.yujian.vision.onvif.model.OnvifPreset(token.trim(),
                    name != null && name.getTextContent() != null ? name.getTextContent().trim() : ""));
        }
        return out;
    }

    /** GetCapabilitiesResponse → Capabilities/{section}@XAddr 提取（PTZ/Replay 共用形状） */
    private static String capsXAddr(Document doc, String section) {
        Element resp = first(doc, OnvifMessages.NS_DEVICE, "GetCapabilitiesResponse");
        if (resp == null) {
            return "";
        }
        Element caps = first(resp, OnvifMessages.NS_SCHEMA, "Capabilities");
        if (caps == null) {
            return "";
        }
        Element el = first(caps, OnvifMessages.NS_SCHEMA, section);
        return el != null && el.getAttribute("XAddr") != null ? el.getAttribute("XAddr").trim() : "";
    }

    /**
     * FindRecordingsResponse → SearchToken（M4-B3）。
     * 设备未实现 Search 服务时回空串（HTTP 200 + Fault 或缺元素, 调用方走降级口径）。
     */
    public static String searchToken(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_SEARCH, "FindRecordingsResponse");
        if (resp == null) {
            return "";
        }
        Element tok = first(resp, OnvifMessages.NS_SCHEMA, "SearchToken");
        if (tok == null) {
            // cam1 实测应答 SearchToken 落在 tse: 命名空间(与请求 trc: 不同前缀), localName 兜底再取一次
            tok = first(resp, OnvifMessages.NS_SEARCH, "SearchToken");
        }
        return tok != null && tok.getTextContent() != null ? tok.getTextContent().trim() : "";
    }

    /**
     * GetRecordingSearchResultsResponse → 录像片段列表（M4-B3）。
     * 每条 RecordingInformation: 时间段优先取 Video track 的 DataFrom/DataTo,
     * 缺 Video track 回退 Recording 级 Earliest/Latest; 坏时间/空 token 条目跳过。
     */
    public static List<com.yujian.vision.onvif.model.OnvifRecordingSegment> recordingSegments(Document doc) {
        List<com.yujian.vision.onvif.model.OnvifRecordingSegment> out = new ArrayList<>();
        Element resp = first(doc, OnvifMessages.NS_SEARCH, "GetRecordingSearchResultsResponse");
        if (resp == null) {
            return out;
        }
        Element list = first(resp, OnvifMessages.NS_SCHEMA, "ResultList");
        if (list == null) {
            return out;
        }
        for (Element rec : children(list, OnvifMessages.NS_SCHEMA, "RecordingInformation")) {
            String token = text(rec, "RecordingToken");
            String sourceName = "";
            Element source = first(rec, OnvifMessages.NS_SCHEMA, "Source");
            if (source != null) {
                sourceName = text(source, "Name");
            }
            LocalDateTime start = null;
            LocalDateTime end = null;
            for (Element track : children(rec, OnvifMessages.NS_SCHEMA, "Track")) {
                Element type = first(track, OnvifMessages.NS_SCHEMA, "TrackType");
                if (type == null || !"Video".equalsIgnoreCase(type.getTextContent().trim())) {
                    continue;
                }
                start = OnvifTime.parse(text(track, "DataFrom"));
                end = OnvifTime.parse(text(track, "DataTo"));
                break;
            }
            if (start == null) {
                start = OnvifTime.parse(text(rec, "EarliestRecording"));
            }
            if (end == null) {
                end = OnvifTime.parse(text(rec, "LatestRecording"));
            }
            if (token.isEmpty() || start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            out.add(new com.yujian.vision.onvif.model.OnvifRecordingSegment(token, sourceName, start, end));
        }
        return out;
    }

    /**
     * GetReplayUriResponse → 回放流地址（M4-B3）。设备未实现/拒绝（cam1 实测 ter:NoRecording）
     * 回空串, 调用方降级为 tracks URI 推导。
     */
    public static String replayUri(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_REPLAY, "GetReplayUriResponse");
        if (resp == null) {
            return "";
        }
        return text(resp, "Uri");
    }

    /**
     * GetRecordingSearchResultsResponse → SearchState（M4-B3: Queued/Searching/Completed;
     * 缺失回空串按未完成处理）。FindRecordings 是异步搜索引擎, 结果须轮询至 Completed 才权威。
     */
    public static String searchState(Document doc) {
        Element resp = first(doc, OnvifMessages.NS_SEARCH, "GetRecordingSearchResultsResponse");
        if (resp == null) {
            return "";
        }
        Element list = first(resp, OnvifMessages.NS_SCHEMA, "ResultList");
        if (list == null) {
            return "";
        }
        Element state = first(list, OnvifMessages.NS_SCHEMA, "SearchState");
        return state != null && state.getTextContent() != null ? state.getTextContent().trim() : "";
    }

    // ---- DOM 小工具（与 OnvifSoapClient 同口径: ns 感知优先, localName 兜底） ----

    private static Element first(Element parent, String ns, String local) {
        // 先直接子级(评审: 后代搜索按文档序取首个同名元素, 有误取风险), 未命中再后代递归
        // —— Response 元素位于 s:Body 下层, 仅直接子级查找不够
        Element hit = OnvifSoapClient.firstByNs(parent, ns, local);
        if (hit == null) {
            hit = OnvifSoapClient.childByLocal(parent, local);
        }
        if (hit != null) {
            return hit;
        }
        hit = descendant(parent, ns, local, false);
        return hit != null ? hit : descendant(parent, null, local, false);
    }

    private static Element descendant(Element parent, String ns, String local, boolean includeSelf) {
        if (includeSelf && local.equals(parent.getLocalName()) && (ns == null || ns.equals(parent.getNamespaceURI()))) {
            return parent;
        }
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) {
                Element hit = descendant(el, ns, local, true);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Element first(Document doc, String ns, String local) {
        return first(doc.getDocumentElement(), ns, local);
    }

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        var nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el
                    && local.equals(el.getLocalName())
                    && (ns.equals(el.getNamespaceURI()) || el.getNamespaceURI() == null)) {
                out.add(el);
            }
        }
        return out;
    }

    private static String text(Element parent, String local) {
        Element el = first(parent, OnvifMessages.NS_SCHEMA, local);
        if (el == null) {
            el = first(parent, OnvifMessages.NS_DEVICE, local);
        }
        return el != null && el.getTextContent() != null ? el.getTextContent().trim() : "";
    }

    private static int intOf(Element el) {
        if (el == null || el.getTextContent() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(el.getTextContent().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
