package com.yujian.vision.gb28181.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yujian.vision.gb28181.message.ManScdpMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 级联-下级出站报文构造（docs/08 §2.2）：Keepalive 心跳与目录应答/通知。
 */
class ManScdpCodecCascadeTest {

    private final ManScdpCodec codec = new ManScdpCodec();

    @Test
    void keepaliveBodyParsesBack() {
        String xml = codec.buildKeepalive("34020000001320000001", 42);
        ManScdpMessage msg = codec.parse(xml);
        assertEquals("Notify", msg.getRootType());
        assertEquals("Keepalive", msg.getCmdType());
        assertEquals(42L, msg.getSn());
        assertEquals("34020000001320000001", msg.getDeviceId());
    }

    @Test
    void catalogResponseMultiPacketFields() {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("DeviceID", "34020000001320000002");
        item.put("Name", "前门<IPC>&1");
        item.put("Parental", "0");
        item.put("Status", "ON");
        String xml = codec.buildCatalogResponse("34020000002000000001", 1001, 3, List.of(item));
        ManScdpMessage msg = codec.parse(xml);
        assertEquals("Response", msg.getRootType());
        assertEquals("Catalog", msg.getCmdType());
        assertEquals(1001L, msg.getSn());
        assertEquals(3, msg.getSumNum());
        assertEquals(1, msg.getItems().size());
        Map<String, String> parsed = msg.getItems().get(0);
        assertEquals("34020000001320000002", parsed.get("DeviceID"));
        assertEquals("ON", parsed.get("Status"));
        // XML 转义回读还原
        assertEquals("前门<IPC>&1", parsed.get("Name"));
    }

    @Test
    void catalogNotifyCarriesItemCount() {
        String xml = codec.buildCatalogNotify("34020000002000000001", 7, List.of());
        ManScdpMessage msg = codec.parse(xml);
        assertEquals("Notify", msg.getRootType());
        assertEquals("Catalog", msg.getCmdType());
        assertTrue(xml.contains("SumNum>0<"), xml);
        assertTrue(xml.contains("DeviceList Num=\"0\""), xml);
    }
}
