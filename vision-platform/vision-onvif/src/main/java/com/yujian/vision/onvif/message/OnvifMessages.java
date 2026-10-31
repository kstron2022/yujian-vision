package com.yujian.vision.onvif.message;

import com.yujian.vision.onvif.soap.WsUsernameToken;

/**
 * ONVIF 请求信封构建（docs/13 §3.1：M4 仅用到的操作逐个手写）。
 * 前缀约定: s=SOAP1.2 信封, tds=Device, trt=Media, tptz=PTZ, trc=Search, trp=Replay, tt=Schema。
 */
public final class OnvifMessages {

    public static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    public static final String NS_DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    public static final String NS_MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    public static final String NS_SCHEMA = "http://www.onvif.org/ver10/schema";
    /** Search 服务（M4-B3 回放检索: FindRecordings/GetRecordingSearchResults） */
    public static final String NS_SEARCH = "http://www.onvif.org/ver10/search/wsdl";
    /** Replay 服务（M4-B3 回放取流: GetReplayUri） */
    public static final String NS_REPLAY = "http://www.onvif.org/ver10/replay/wsdl";
    /** PTZ 服务（云台 ContinuousMove/Stop/预置位）。注意是 ver20——2026-09-25 球机实测缺陷:
     *  PTZ 操作曾误用 Media 命名空间(trt), 设备按未实现操作回 Fault "Optional Action Not Implemented",
     *  cam1 无云台时代无法区分被误定性为"硬件不支持"。 */
    public static final String NS_PTZ = "http://www.onvif.org/ver20/ptz/wsdl";
    /** Imaging 服务（聚焦控制 Move/Stop——聚焦属 Imaging 而非 PTZ 服务, 2026-09-25 现场补齐） */
    public static final String NS_IMAGING = "http://www.onvif.org/ver20/imaging/wsdl";

    private OnvifMessages() {
    }

    /** 信封骨架：安全头 + 指定操作体（信封声明含 search/replay, 全部操作共用无妨） */
    private static String envelope(String operationXml, String username, String password) {
        WsUsernameToken.Token token = WsUsernameToken.generate(username, password, java.time.Instant.now());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"" + NS_SOAP + "\""
                + " xmlns:tds=\"" + NS_DEVICE + "\""
                + " xmlns:trt=\"" + NS_MEDIA + "\""
                + " xmlns:trc=\"" + NS_SEARCH + "\""
                + " xmlns:trp=\"" + NS_REPLAY + "\""
                + " xmlns:tptz=\"" + NS_PTZ + "\""
                + " xmlns:timg=\"" + NS_IMAGING + "\""
                + " xmlns:tt=\"" + NS_SCHEMA + "\">"
                + "<s:Header>" + WsUsernameToken.headerXml(token) + "</s:Header>"
                + "<s:Body>" + operationXml + "</s:Body>"
                + "</s:Envelope>";
    }

    public static String getDeviceInformation(String username, String password) {
        return envelope("<tds:GetDeviceInformation/>", username, password);
    }

    public static String getVideoSources(String username, String password) {
        return envelope("<tds:GetVideoSources/>", username, password);
    }

    public static String getProfiles(String username, String password) {
        return envelope("<trt:GetProfiles/>", username, password);
    }

    /**
     * GetStreamUri（M4-B2 点播, docs/13 §5.1）：指定档位取流地址。
     * Transport 协议固定 RTSP（ZLM addStreamProxy 拉流; RTP over TCP 由 ZLM 侧 rtp_type 决定）。
     */
    public static String getStreamUri(String username, String password, String profileToken) {
        return envelope("<trt:GetStreamUri>"
                + "<trt:StreamSetup>"
                + "<tt:Stream>RTP-Unicast</tt:Stream>"
                + "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>"
                + "</trt:StreamSetup>"
                + "<trt:ProfileToken>" + xmlEscape(profileToken) + "</trt:ProfileToken>"
                + "</trt:GetStreamUri>", username, password);
    }

    /** GetSnapshotUri（M4-B2 快照, docs/13 §5.2）：指定档位取抓图地址（HTTP JPEG） */
    public static String getSnapshotUri(String username, String password, String profileToken) {
        return envelope("<trt:GetSnapshotUri>"
                + "<trt:ProfileToken>" + xmlEscape(profileToken) + "</trt:ProfileToken>"
                + "</trt:GetSnapshotUri>", username, password);
    }

    /** GetCapabilities（M4-B2 PTZ / M4-B3 Replay 服务地址：取 XAddr） */
    public static String getCapabilities(String username, String password) {
        return envelope("<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>",
                username, password);
    }

    /**
     * FindRecordings（M4-B3 回放检索, docs/13 §5.3）：按时间窗发起录像搜索, 应答回 SearchToken。
     * 注意: 相机/NVR 对本操作的路由在 device_service 端点即可达（cam1 V5.5.104 实测）, 且
     * <b>不要</b>调 Recording 服务的 GetRecordings——该固件对它直接崩机重启（2026-09-19 实证）。
     */
    public static String findRecordings(String username, String password,
            String fromXsUtc, String untilXsUtc) {
        return envelope("<trc:FindRecordings>"
                + "<trc:Scope>"
                + "<tt:TimeRange>"
                + "<tt:From>" + fromXsUtc + "</tt:From>"
                + "<tt:Until>" + untilXsUtc + "</tt:Until>"
                + "</tt:TimeRange>"
                + "</trc:Scope>"
                + "</trc:FindRecordings>", username, password);
    }

    /** GetRecordingSearchResults（M4-B3）：按 SearchToken 取结果（MaxResults 上限防大 NVR 拖垮应答） */
    public static String getRecordingSearchResults(String username, String password,
            String searchToken) {
        return envelope("<trc:GetRecordingSearchResults>"
                + "<trc:SearchToken>" + xmlEscape(searchToken) + "</trc:SearchToken>"
                + "<trc:MinResults>1</trc:MinResults>"
                + "<trc:MaxResults>200</trc:MaxResults>"
                + "<tt:KeepAliveTime>PT10S</tt:KeepAliveTime>"
                + "</trc:GetRecordingSearchResults>", username, password);
    }

    /** GetReplayUri（M4-B3 回放取流）：指定档位取回放流地址（不支持的固件回 Fault, 调用方降级推导） */
    public static String getReplayUri(String username, String password, String profileToken) {
        return envelope("<trp:GetReplayUri>"
                + "<trp:ProfileToken>" + xmlEscape(profileToken) + "</trp:ProfileToken>"
                + "</trp:GetReplayUri>", username, password);
    }

    /** ContinuousMove（M4-B2 云台）：PanTilt/Zoom 速度 ∈ [-1,1], 语义 UP=y+ DOWN=y- LEFT=x- RIGHT=x+。
     *  操作元素必须带 PTZ 命名空间（tptz）, Velocity 形状 = tt:PTZSpeed（PanTilt 二维 + Zoom 一维）。 */
    public static String continuousMove(String username, String password, String profileToken,
            double panX, double panY, double zoom) {
        return envelope("<tptz:ContinuousMove>"
                + "<tptz:ProfileToken>" + xmlEscape(profileToken) + "</tptz:ProfileToken>"
                + "<tptz:Velocity>"
                + "<tt:PanTilt x=\"" + panX + "\" y=\"" + panY + "\"/>"
                + "<tt:Zoom x=\"" + zoom + "\"/>"
                + "</tptz:Velocity></tptz:ContinuousMove>", username, password);
    }

    /** Stop（M4-B2 云台停止：PanTilt/Zoom 全停） */
    public static String ptzStop(String username, String password, String profileToken) {
        return envelope("<tptz:Stop>"
                + "<tptz:ProfileToken>" + xmlEscape(profileToken) + "</tptz:ProfileToken>"
                + "</tptz:Stop>", username, password);
    }

    /** SetPreset / GotoPreset / RemovePreset（M4-B2 预置位, PresetToken=GB 预置位号字符串）。
     *  SetPreset 的 PresetName 为 ver20 必填元素, 用预置位号兜底命名。 */
    public static String ptzPreset(String username, String password, String profileToken,
            String operation, String presetToken) {
        String presetName = "SetPreset".equals(operation)
                ? "<tptz:PresetName>Preset " + xmlEscape(presetToken) + "</tptz:PresetName>"
                : "";
        return envelope("<tptz:" + operation + ">"
                + "<tptz:ProfileToken>" + xmlEscape(profileToken) + "</tptz:ProfileToken>"
                + presetName
                + "<tptz:PresetToken>" + xmlEscape(presetToken) + "</tptz:PresetToken>"
                + "</tptz:" + operation + ">", username, password);
    }

    /** GetPresets 预置位检索（2026-09-25 现场补齐——此前只有 SET/GOTO/DELETE, 查询走 GB SIP 对 ONVIF 设备不通） */
    public static String getPresets(String username, String password, String profileToken) {
        return envelope("<tptz:GetPresets>"
                + "<tptz:ProfileToken>" + xmlEscape(profileToken) + "</tptz:ProfileToken>"
                + "</tptz:GetPresets>", username, password);
    }

    /** Imaging Move 聚焦（focusX ∈ [-1,1], 正=近聚焦/负=远聚焦; 目标是 VideoSourceToken 而非 ProfileToken） */
    public static String imagingMove(String username, String password, String videoSourceToken, double focusX) {
        return envelope("<timg:Move>"
                + "<timg:VideoSourceToken>" + xmlEscape(videoSourceToken) + "</timg:VideoSourceToken>"
                + "<timg:Focus><tt:ContinuousFocus x=\"" + focusX + "\"/></timg:Focus>"
                + "</timg:Move>", username, password);
    }

    /** Imaging Stop 聚焦停（PTZ Stop 不停聚焦——两个服务各自惯性运动, 松开键需双停） */
    public static String imagingStop(String username, String password, String videoSourceToken) {
        return envelope("<timg:Stop>"
                + "<timg:VideoSourceToken>" + xmlEscape(videoSourceToken) + "</timg:VideoSourceToken>"
                + "</timg:Stop>", username, password);
    }

    /** 文本入参 XML 转义（profile token 等来自设备应答, 回填进请求体防注入） */
    private static String xmlEscape(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '\'' -> sb.append("&apos;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
