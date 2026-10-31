package com.yujian.vision.service.media.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.yujian.vision.service.record.CloudRecordService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F2 回归: 真实 ZLM on_play params 为查询串字符串, 兼容 Map 形状 */
class HookParamsParseTest {

    @Test
    @DisplayName("真实 ZLM 形状: params 为查询串字符串")
    void queryStringShape() {
        String[] r = HookService.parseParams("sign=abc123&expire=1725600000&other=x");
        assertArrayEquals(new String[]{"abc123", "1725600000"}, r);
    }

    @Test
    @DisplayName("Map 形状(平台测试工具)")
    void mapShape() {
        String[] r = HookService.parseParams(Map.of("sign", "s1", "expire", "42"));
        assertArrayEquals(new String[]{"s1", "42"}, r);
    }

    @Test
    @DisplayName("缺失字段返回 null")
    void missingFields() {
        String[] r = HookService.parseParams("expire=42");
        assertNull(r[0]);
        assertEquals("42", r[1]);
    }

    @Test
    @DisplayName("null params")
    void nullParams() {
        String[] r = HookService.parseParams(null);
        assertNull(r[0]);
        assertNull(r[1]);
    }

    @Test
    @DisplayName("on_record_mp4 报文归一化: ZLM 官文字段名原样取值")
    void mp4BodyOfficialFieldNames() {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("mediaServerId", "zlm-1");
        body.put("app", "rtp");
        body.put("stream", "gb_play_1");
        body.put("file_name", "10-00-00.mp4");
        body.put("file_path", "/data/record/rtp/2026-09-09/10-00-00.mp4");
        body.put("file_size", 1913597);
        body.put("start_time", 1789000000);
        body.put("time_len", 60.4D);

        CloudRecordService.HookMp4Chunk c = HookService.parseMp4Body(body);

        assertEquals("gb_play_1", c.streamId());
        assertEquals("rtp", c.app());
        assertEquals("zlm-1", c.mediaServerId());
        assertEquals("/data/record/rtp/2026-09-09/10-00-00.mp4", c.filePath());
        assertEquals("10-00-00.mp4", c.fileName());
        assertEquals(1913597L, c.fileSize());
        assertEquals(60.4D, c.timeLen());
        assertEquals(1789000000L, c.startEpoch());
    }

    @Test
    @DisplayName("on_record_mp4: 兼容 filepath/duration 拼写, 缺字段不炸")
    void mp4BodyToleratesAltSpellingsAndMissingFields() {
        assertNull(HookService.parseMp4Body(null));
        assertNull(HookService.parseMp4Body(Map.of()).filePath());

        CloudRecordService.HookMp4Chunk c = HookService.parseMp4Body(Map.of(
                "stream", "s1",
                "filepath", "/tmp/a.mp4",
                "duration", 30));
        assertEquals("/tmp/a.mp4", c.filePath());
        assertEquals(30D, c.timeLen());
        assertNull(c.fileSize());
        assertNull(c.startEpoch());
    }
}
