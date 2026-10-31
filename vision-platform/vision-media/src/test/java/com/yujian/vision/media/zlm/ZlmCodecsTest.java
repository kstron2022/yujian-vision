package com.yujian.vision.media.zlm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** B5: ZLM codec name normalization / video track extraction boundary tests. */
class ZlmCodecsTest {

    @Test
    @DisplayName("normalize maps 265/HEVC to H265, 264/AVC to H264, passthrough otherwise")
    void normalize() {
        assertEquals("H265", ZlmCodecs.normalizeVideoCodec("H265"));
        assertEquals("H265", ZlmCodecs.normalizeVideoCodec("Hevc"));
        assertEquals("H265", ZlmCodecs.normalizeVideoCodec("h265"));
        assertEquals("H264", ZlmCodecs.normalizeVideoCodec("H264"));
        assertEquals("H264", ZlmCodecs.normalizeVideoCodec("AVC"));
        assertEquals("AAC", ZlmCodecs.normalizeVideoCodec("AAC"));
        assertEquals("", ZlmCodecs.normalizeVideoCodec(""));
        assertEquals("", ZlmCodecs.normalizeVideoCodec(null));
    }

    @Test
    @DisplayName("extract prefers codec_type=0; fallback only accepts missing codec_type")
    void extract() {
        assertEquals("H265", ZlmCodecs.extractVideoCodec(List.of(
                Map.of("codec_id_name", "H265", "codec_type", "0"),
                Map.of("codec_id_name", "AAC"))));
        assertEquals("H264", ZlmCodecs.extractVideoCodec(List.of(
                Map.of("codec_id_name", "H264"))));
        assertEquals("", ZlmCodecs.extractVideoCodec(List.of(
                Map.of("codec_id_name", "AAC", "codec_type", "1"))));
        assertEquals("", ZlmCodecs.extractVideoCodec(null));
        assertEquals("", ZlmCodecs.extractVideoCodec("not-a-list"));
    }

}
