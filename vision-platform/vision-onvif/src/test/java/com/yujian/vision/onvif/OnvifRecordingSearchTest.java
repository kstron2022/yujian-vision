package com.yujian.vision.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.onvif.message.OnvifMessages;
import com.yujian.vision.onvif.message.OnvifParsers;
import com.yujian.vision.onvif.model.OnvifRecordingSegment;
import com.yujian.vision.onvif.soap.OnvifSoapClient;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * 回放检索/取流解析 fixture 测试（M4-B3）。fixture 取自 cam1 V5.5.104 实测报文形态:
 * 应答命名空间前缀与请求不同（SearchToken 落 tse: 而非请求的 trc:）。
 */
class OnvifRecordingSearchTest {

    private static final String ENVELOPE_HEAD =
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                    + " xmlns:tse=\"http://www.onvif.org/ver10/search/wsdl\""
                    + " xmlns:trc=\"http://www.onvif.org/ver10/search/wsdl\""
                    + " xmlns:trp=\"http://www.onvif.org/ver10/replay/wsdl\""
                    + " xmlns:tt=\"http://www.onvif.org/ver10/schema\"><s:Body>";

    private static Document parse(String body) {
        return OnvifSoapClient.newDocument(ENVELOPE_HEAD + body + "</s:Body></s:Envelope>");
    }

    @Test
    @DisplayName("OnvifTime.parse: UTC Z / 无时区 / 坏输入")
    void timeParse() {
        LocalDateTime utc = OnvifTime.parse("2026-09-18T17:11:29Z");
        LocalDateTime expect = java.time.OffsetDateTime.parse("2026-09-18T17:11:29Z")
                .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
        assertEquals(expect, utc);
        assertEquals(LocalDateTime.parse("2026-09-18T17:11:29"),
                OnvifTime.parse("2026-09-18T17:11:29"));
        assertNull(OnvifTime.parse("not-a-time"));
        assertNull(OnvifTime.parse(null));
    }

    @Test
    @DisplayName("OnvifTime: toXsDateTime/toMtime 均转 UTC 形态")
    void timeFormat() {
        // 本地 08:00 (+8) → UTC 00:00
        LocalDateTime local = LocalDateTime.of(2026, 9, 19, 8, 0, 0);
        String xs = OnvifTime.toXsDateTime(local);
        String mt = OnvifTime.toMtime(local);
        assertTrue(xs.endsWith("T00:00:00Z"), "xs=" + xs);
        assertEquals("20260919T000000Z", mt);
    }

    @Test
    @DisplayName("searchToken: tse: 应答形态（cam1 实测）与 trc: 标准形态都取到")
    void searchToken() {
        Document doc = parse("<tse:FindRecordingsResponse>"
                + "<tse:SearchToken>RecordingSearchToken_0</tse:SearchToken>"
                + "</tse:FindRecordingsResponse>");
        assertEquals("RecordingSearchToken_0", OnvifParsers.searchToken(doc));
        Document doc2 = parse("<trc:FindRecordingsResponse>"
                + "<trc:SearchToken>tok1</trc:SearchToken>"
                + "</trc:FindRecordingsResponse>");
        assertEquals("tok1", OnvifParsers.searchToken(doc2));
    }

    @Test
    @DisplayName("recordingSegments: Video track 时间段优先, 坏条目跳过")
    void recordingSegments() {
        Document doc = parse("<tse:GetRecordingSearchResultsResponse><tse:ResultList>"
                + "<tt:SearchState>Completed</tt:SearchState>"
                + "<tt:RecordingInformation>"
                + "<tt:RecordingToken>OnvifRecordingToken_1</tt:RecordingToken>"
                + "<tt:Source><tt:Name>IpCamera_1</tt:Name></tt:Source>"
                + "<tt:EarliestRecording>2026-09-18T10:00:00Z</tt:EarliestRecording>"
                + "<tt:LatestRecording>2026-09-18T11:00:00Z</tt:LatestRecording>"
                + "<tt:Track><tt:TrackType>Video</tt:TrackType>"
                + "<tt:DataFrom>2026-09-18T10:05:00Z</tt:DataFrom>"
                + "<tt:DataTo>2026-09-18T10:55:00Z</tt:DataTo></tt:Track>"
                + "</tt:RecordingInformation>"
                + "<tt:RecordingInformation><tt:RecordingToken>bad</tt:RecordingToken></tt:RecordingInformation>"
                + "</tse:ResultList></tse:GetRecordingSearchResultsResponse>");
        List<OnvifRecordingSegment> list = OnvifParsers.recordingSegments(doc);
        assertEquals(1, list.size());
        OnvifRecordingSegment seg = list.get(0);
        assertEquals("OnvifRecordingToken_1", seg.recordingToken());
        assertEquals("IpCamera_1", seg.sourceName());
        // Video track 时间段优先于 Recording 级 Earliest/Latest（差 5 分钟）
        assertTrue(seg.start().isBefore(seg.end()));
    }

    @Test
    @DisplayName("replayUri: 正常取 Uri, Fault 缺元素回空串")
    void replayUri() {
        Document ok = parse("<trp:GetReplayUriResponse>"
                + "<tt:MediaUri><tt:Uri>rtsp://10.0.0.9:554/replay</tt:Uri></tt:MediaUri>"
                + "</trp:GetReplayUriResponse>");
        assertEquals("rtsp://10.0.0.9:554/replay", OnvifParsers.replayUri(ok));
        Document fault = parse("<s:Fault/>");
        assertEquals("", OnvifParsers.replayUri(fault));
    }

    @Test
    @DisplayName("报文构建: FindRecordings 时间转 UTC / token 转义")
    void messageShapes() {
        String find = OnvifMessages.findRecordings("u", "p",
                "2026-09-18T00:00:00Z", "2026-09-19T00:00:00Z");
        assertTrue(find.contains("<tt:From>2026-09-18T00:00:00Z</tt:From>"));
        assertTrue(find.contains("<trc:FindRecordings>"));
        String res = OnvifMessages.getRecordingSearchResults("u", "p", "tok<1>");
        assertTrue(res.contains("tok&lt;1&gt;"));
        String rep = OnvifMessages.getReplayUri("u", "p", "Profile_1");
        assertTrue(rep.contains("<trp:ProfileToken>Profile_1</trp:ProfileToken>"));
    }
}
