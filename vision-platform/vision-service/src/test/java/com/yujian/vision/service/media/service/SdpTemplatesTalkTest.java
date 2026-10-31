package com.yujian.vision.service.media.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 对讲 SDP 模板断言（M3-B5, GB/T 28181-2022 Talk） */
class SdpTemplatesTalkTest {

    @Test
    void talkUdp_containsTalkSessionAndPcma() {
        String sdp = SdpTemplates.talkUdp("34020000002000000001", "172.26.240.1", 30001, "0123456789");
        assertThat(sdp).contains("s=Talk");
        assertThat(sdp).contains("c=IN IP4 172.26.240.1");
        assertThat(sdp).contains("m=audio 30001 RTP/AVP 8");
        assertThat(sdp).contains("a=rtpmap:8 PCMA/8000");
        assertThat(sdp).contains("a=sendrecv");
        assertThat(sdp).contains("y=0123456789");
        assertThat(sdp).doesNotContain("m=video");
    }
}
