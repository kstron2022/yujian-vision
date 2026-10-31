package com.yujian.vision.gb28181.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.yujian.vision.gb28181.message.ManScdpMessage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PresetQuery 报文构造与应答解析（docs/09 M1-8 挂账, M2-B3）：
 * build 风格 mirror buildCatalogQuery; 应答 PresetList/Item 平铺进 items（PresetID/PresetName）,
 * 并回归校验 DeviceList/RecordList 既有行为不变。
 */
class ManScdpCodecPresetQueryTest {

    private final ManScdpCodec codec = new ManScdpCodec();

    private static final String CRLF = "\r\n";

    @Test
    @DisplayName("buildPresetQuery: 根节点 Query + CmdType/SN/DeviceID, 风格同 buildCatalogQuery")
    void buildShape() {
        String xml = codec.buildPresetQuery("34020000001320000001", 42L);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + CRLF
                + "<Query>" + CRLF
                + "<CmdType>PresetQuery</CmdType>" + CRLF
                + "<SN>42</SN>" + CRLF
                + "<DeviceID>34020000001320000001</DeviceID>" + CRLF
                + "</Query>" + CRLF, xml);
    }

    @Test
    @DisplayName("查询报文 parse 回环: rootType/CmdType/SN/DeviceID 命中, 无 items")
    void buildParseRoundTrip() {
        ManScdpMessage m = codec.parse(codec.buildPresetQuery("34020000001320000001", 7L));
        assertEquals("Query", m.getRootType());
        assertEquals("PresetQuery", m.getCmdType());
        assertEquals(7L, m.getSn());
        assertEquals("34020000001320000001", m.getDeviceId());
        assertNull(m.getItems());
    }

    @Test
    @DisplayName("应答 PresetList 多 Item: 平铺 items, PresetID/PresetName 均可取")
    void parseResponseMultipleItems() {
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>" + CRLF
                + "<RESPONSE>" + CRLF
                + "<CmdType>PresetQuery</CmdType>" + CRLF
                + "<SN>17430</SN>" + CRLF
                + "<DeviceID>34020000001320000001</DeviceID>" + CRLF
                + "<PresetList Num=\"2\">" + CRLF
                + "<Item>" + CRLF
                + "<PresetID>1</PresetID>" + CRLF
                + "<PresetName>gate</PresetName>" + CRLF
                + "</Item>" + CRLF
                + "<Item>" + CRLF
                + "<PresetID>2</PresetID>" + CRLF
                + "<PresetName>lobby</PresetName>" + CRLF
                + "</Item>" + CRLF
                + "</PresetList>" + CRLF
                + "</RESPONSE>" + CRLF;
        ManScdpMessage m = codec.parse(xml);
        assertEquals("RESPONSE", m.getRootType());
        assertEquals("PresetQuery", m.getCmdType());
        assertEquals(17430L, m.getSn());
        assertEquals(2, m.getItems().size());
        assertEquals("1", m.getItems().get(0).get("PresetID"));
        assertEquals("gate", m.getItems().get(0).get("PresetName"));
        assertEquals("2", m.getItems().get(1).get("PresetID"));
        assertEquals("lobby", m.getItems().get(1).get("PresetName"));
    }

    @Test
    @DisplayName("应答单 Item（非数组形态）: 解析为 1 条")
    void parseResponseSingleItem() {
        String xml = "<RESPONSE><CmdType>PresetQuery</CmdType><SN>1</SN>"
                + "<DeviceID>d</DeviceID>"
                + "<PresetList><Item><PresetID>9</PresetID><PresetName>only</PresetName></Item></PresetList>"
                + "</RESPONSE>";
        ManScdpMessage m = codec.parse(xml);
        assertEquals(1, m.getItems().size());
        assertEquals("9", m.getItems().get(0).get("PresetID"));
        assertEquals("only", m.getItems().get(0).get("PresetName"));
    }

    @Test
    @DisplayName("空 PresetList（无预置位）: items 为 null, 业务层按空列表处理")
    void parseEmptyPresetList() {
        String xml = "<RESPONSE><CmdType>PresetQuery</CmdType><SN>2</SN>"
                + "<DeviceID>d</DeviceID><PresetList Num=\"0\"/></RESPONSE>";
        ManScdpMessage m = codec.parse(xml);
        assertNull(m.getItems());
        assertDoesNotThrow(() -> m.getItems());
    }

    @Test
    @DisplayName("回归: DeviceList/RecordList 应答解析行为不变")
    void catalogAndRecordInfoUnchanged() {
        String catalog = "<RESPONSE><CmdType>Catalog</CmdType><SN>3</SN><DeviceID>d</DeviceID>"
                + "<DeviceList Num=\"1\"><Item><DeviceID>34020000001320000001</DeviceID>"
                + "<Name>cam</Name><PTZType>1</PTZType></Item></DeviceList></RESPONSE>";
        ManScdpMessage c = codec.parse(catalog);
        assertEquals(1, c.getItems().size());
        assertEquals("cam", c.getItems().get(0).get("Name"));
        assertEquals("1", c.getItems().get(0).get("PTZType"));

        String record = "<RESPONSE><CmdType>RecordInfo</CmdType><SN>4</SN><DeviceID>d</DeviceID>"
                + "<SumNum>1</SumNum>"
                + "<RecordList Num=\"1\"><Item><DeviceID>34020000001320000001</DeviceID>"
                + "<Name>rec</Name><StartTime>2026-09-09 10:00:00</StartTime>"
                + "<EndTime>2026-09-09 10:30:00</EndTime></Item></RecordList></RESPONSE>";
        ManScdpMessage r = codec.parse(record);
        assertEquals(1, r.getSumNum());
        assertEquals(1, r.getItems().size());
        assertEquals("rec", r.getItems().get(0).get("Name"));
    }

    @Test
    @DisplayName("PresetList 与 DeviceList 同时缺失 → items 为 null")
    void noListContainer() {
        ManScdpMessage m = codec.parse("<RESPONSE><CmdType>PresetQuery</CmdType><SN>5</SN>"
                + "<DeviceID>d</DeviceID></RESPONSE>");
        assertNull(m.getItems());
        assertTrue(m.getSn() == 5L);
    }

    @Test
    @DisplayName("Item 内 PresetName 缺省 → 字段缺失容错（不进字段表）")
    void itemNameMissing() {
        ManScdpMessage m = codec.parse("<RESPONSE><CmdType>PresetQuery</CmdType><SN>6</SN>"
                + "<DeviceID>d</DeviceID>"
                + "<PresetList><Item><PresetID>3</PresetID></Item></PresetList></RESPONSE>");
        Map<String, String> item = m.getItems().get(0);
        assertEquals("3", item.get("PresetID"));
        assertNull(item.get("PresetName"));
    }
}
