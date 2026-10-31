package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.onvif.message.OnvifMessages;
import com.yujian.vision.onvif.message.OnvifParsers;
import com.yujian.vision.onvif.model.OnvifDeviceInfo;
import com.yujian.vision.onvif.model.OnvifProfile;
import com.yujian.vision.onvif.model.OnvifVideoSource;
import com.yujian.vision.onvif.soap.OnvifSoapClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** 应答解析 fixture 测试（海康风格命名空间 + 厂商容错路径） */
class OnvifParsersTest {

    private static final String ENVELOPE_HEAD =
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                    + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
                    + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                    + " xmlns:tt=\"http://www.onvif.org/ver10/schema\"><s:Body>";

    private static Document parse(String body) {
        return OnvifSoapClient.newDocument(ENVELOPE_HEAD + body + "</s:Body></s:Envelope>");
    }

    @Test
    @DisplayName("GetDeviceInformation 应答解析")
    void deviceInfo() {
        Document doc = parse("<tds:GetDeviceInformationResponse>"
                + "<tds:Manufacturer>Hikvision</tds:Manufacturer>"
                + "<tds:Model>IPC-T1</tds:Model>"
                + "<tds:FirmwareVersion>V5.7.14</tds:FirmwareVersion>"
                + "<tds:SerialNumber>SN123456</tds:SerialNumber>"
                + "<tds:HardwareId>0000</tds:HardwareId>"
                + "</tds:GetDeviceInformationResponse>");
        OnvifDeviceInfo info = OnvifParsers.deviceInfo(doc);
        assertEquals("Hikvision", info.manufacturer());
        assertEquals("IPC-T1", info.model());
        assertEquals("V5.7.14", info.firmware());
        assertEquals("SN123456", info.serialNumber());
    }

    @Test
    @DisplayName("GetVideoSources 应答解析: token 属性")
    void videoSources() {
        Document doc = parse("<tds:GetVideoSourcesResponse>"
                + "<tt:VideoSource token=\"VideoSource_1\"/>"
                + "<tt:VideoSource token=\"VideoSource_2\"/>"
                + "</tds:GetVideoSourcesResponse>");
        List<OnvifVideoSource> list = OnvifParsers.videoSources(doc);
        assertEquals(2, list.size());
        assertEquals("VideoSource_1", list.get(0).token());
    }

    @Test
    @DisplayName("GetProfiles 应答解析: SourceToken 关联/分辨率/编码/PTZ 标记")
    void profiles() {
        Document doc = parse("<trt:GetProfilesResponse>"
                + "<tt:Profile token=\"Profile_1\" fixed=\"true\">"
                + "<tt:Name>mainStream</tt:Name>"
                + "<tt:VideoSourceConfiguration><tt:SourceToken>VideoSource_1</tt:SourceToken></tt:VideoSourceConfiguration>"
                + "<tt:VideoEncoderConfiguration><tt:Encoding>H265</tt:Encoding>"
                + "<tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1440</tt:Height></tt:Resolution>"
                + "</tt:VideoEncoderConfiguration><tt:PTZConfiguration/></tt:Profile>"
                + "<tt:Profile token=\"Profile_2\" fixed=\"true\">"
                + "<tt:Name>subStream</tt:Name>"
                + "<tt:VideoSourceConfiguration><tt:SourceToken>VideoSource_1</tt:SourceToken></tt:VideoSourceConfiguration>"
                + "<tt:VideoEncoderConfiguration><tt:Encoding>H264</tt:Encoding>"
                + "<tt:Resolution><tt:Width>704</tt:Width><tt:Height>576</tt:Height></tt:Resolution>"
                + "</tt:VideoEncoderConfiguration></tt:Profile>"
                + "</trt:GetProfilesResponse>");
        List<OnvifProfile> list = OnvifParsers.profiles(doc);
        assertEquals(2, list.size());
        OnvifProfile main = list.get(0);
        assertEquals("Profile_1", main.token());
        assertEquals("VideoSource_1", main.sourceToken());
        assertEquals(2560, main.width());
        assertEquals(1440, main.height());
        assertEquals("H265", main.encoding());
        assertTrue(main.ptz());
        OnvifProfile sub = list.get(1);
        assertEquals(704, sub.width());
        assertFalse(sub.ptz());
    }

    @Test
    @DisplayName("请求信封: 安全头+操作体齐备且用户名转义")
    void envelopeShape() {
        String xml = OnvifMessages.getDeviceInformation("us<r>", "p");
        assertTrue(xml.contains("<tds:GetDeviceInformation/>"));
        assertTrue(xml.contains("us&lt;r&gt;"));
        assertTrue(xml.contains("PasswordDigest"));
    }

    @Test
    @DisplayName("海康 V5.5.x 固件形态(cam1 实证): Profile 元素写成 trt:Profiles(复数) 仍可解析")
    void hikvisionPluralProfiles() {
        Document doc = parse("<trt:GetProfilesResponse>"
                + "<trt:Profiles token=\"Profile_1\" fixed=\"true\">"
                + "<tt:Name>mainStream</tt:Name>"
                + "<tt:VideoSourceConfiguration token=\"VideoSourceToken\"><tt:SourceToken>VideoSource_1</tt:SourceToken></tt:VideoSourceConfiguration>"
                + "<tt:VideoEncoderConfiguration token=\"VideoEncoderToken_1\" encoding=\"H265\"><tt:Encoding>H264</tt:Encoding>"
                + "<tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>"
                + "</tt:VideoEncoderConfiguration></trt:Profiles>"
                + "</trt:GetProfilesResponse>");
        List<OnvifProfile> list = OnvifParsers.profiles(doc);
        assertEquals(1, list.size());
        assertEquals("Profile_1", list.get(0).token());
        assertEquals("VideoSource_1", list.get(0).sourceToken());
        assertEquals(1920, list.get(0).width());
        assertEquals("H264", list.get(0).encoding());
    }

    @Test
    @DisplayName("PTZ 报文: ContinuousMove 速度归一/Stop/预置位三操作")
    void ptzMessages() {
        // 操作元素必须在 PTZ 命名空间 ver20/ptz/wsdl——2026-09-25 球机实测: 曾误用 Media
        // 命名空间(trt), 设备按未实现操作回 Fault "Optional Action Not Implemented"
        String move = OnvifMessages.continuousMove("u", "p", "Profile_1", 0.5, -1.0, 0.0);
        assertTrue(move.contains("xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""));
        assertTrue(move.contains("<tptz:ContinuousMove>"));
        assertTrue(move.contains("<tptz:ProfileToken>Profile_1</tptz:ProfileToken>"));
        assertTrue(move.contains("<tt:PanTilt x=\"0.5\" y=\"-1.0\"/>"));
        assertTrue(move.contains("<tt:Zoom x=\"0.0\"/>"));
        String stop = OnvifMessages.ptzStop("u", "p", "Profile_1");
        assertTrue(stop.contains("<tptz:Stop>"));
        assertTrue(stop.contains("<tptz:ProfileToken>Profile_1</tptz:ProfileToken>"));
        String preset = OnvifMessages.ptzPreset("u", "p", "Profile_1", "GotoPreset", "3");
        assertTrue(preset.contains("<tptz:GotoPreset>"));
        assertTrue(preset.contains("<tptz:PresetToken>3</tptz:PresetToken>"));
        // SetPreset 的 PresetName 为 ver20 必填元素
        String setPreset = OnvifMessages.ptzPreset("u", "p", "Profile_1", "SetPreset", "3");
        assertTrue(setPreset.contains("<tptz:PresetName>Preset 3</tptz:PresetName>"));
    }

    @Test
    @DisplayName("PTZ XAddr 解析 + 缺失回空串")
    void ptzXAddr() {
        Document doc = parse("<tds:GetCapabilitiesResponse>"
                + "<tt:Capabilities>"
                + "<tt:Device XAddr=\"http://10.0.0.9:50080/onvif/device_service\"/>"
                + "<tt:PTZ XAddr=\"http://10.0.0.9:50080/onvif/device_service\"/>"
                + "</tt:Capabilities></tds:GetCapabilitiesResponse>");
        assertEquals("http://10.0.0.9:50080/onvif/device_service", OnvifParsers.ptzXAddr(doc));
        Document noPtz = parse("<tds:GetCapabilitiesResponse><tt:Capabilities/></tds:GetCapabilitiesResponse>");
        assertEquals("", OnvifParsers.ptzXAddr(noPtz));
    }

    @Test
    @DisplayName("GetPresets 应答解析: token/name 提取, 无 token 条目跳过")
    void presets() {
        Document doc = parse("<tptz:GetPresetsResponse xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<tptz:Preset token=\"1\"><tt:Name>大门</tt:Name></tptz:Preset>"
                + "<tptz:Preset token=\"2\"><tt:Name>Presets 2</tt:Name></tptz:Preset>"
                + "<tptz:Preset><tt:Name>无token跳过</tt:Name></tptz:Preset>"
                + "</tptz:GetPresetsResponse>");
        var list = OnvifParsers.presets(doc);
        assertEquals(2, list.size());
        assertEquals("1", list.get(0).token());
        assertEquals("大门", list.get(0).name());
        assertEquals("2", list.get(1).token());
        Document noResp = parse("<tds:GetCapabilitiesResponse/>");
        assertTrue(OnvifParsers.presets(noResp).isEmpty());
    }

    @Test
    @DisplayName("GetStreamUri 应答解析: tt:MediaUri/tt:Uri")
    void streamUri() {
        Document doc = parse("<trt:GetStreamUriResponse>"
                + "<tt:MediaUri><tt:Uri>rtsp://10.0.0.9:554/Streaming/Channels/101</tt:Uri>"
                + "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect></tt:MediaUri>"
                + "</trt:GetStreamUriResponse>");
        assertEquals("rtsp://10.0.0.9:554/Streaming/Channels/101", OnvifParsers.mediaUri(doc));
    }

    @Test
    @DisplayName("GetSnapshotUri 应答解析 + 未实现该操作的固件回空串")
    void snapshotUri() {
        Document doc = parse("<trt:GetSnapshotUriResponse>"
                + "<tt:MediaUri><tt:Uri>http://10.0.0.9/onvif-http/snapshot?Profile_1</tt:Uri></tt:MediaUri>"
                + "</trt:GetSnapshotUriResponse>");
        assertEquals("http://10.0.0.9/onvif-http/snapshot?Profile_1", OnvifParsers.mediaUri(doc));
        Document faultDoc = parse("<s:Fault><s:Reason><s:Text>ter:ActionNotSupported</s:Text></s:Reason></s:Fault>");
        assertEquals("", OnvifParsers.mediaUri(faultDoc));
    }
}
