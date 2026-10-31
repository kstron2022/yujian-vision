package com.yujian.vision.gb28181.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yujian.vision.gb28181.message.ManScdpMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录订阅 NOTIFY 报文解析（docs/08 §3.4, M3-B3 阶段4）：
 * Notify/Catalog 的 Item 属性（2022 Event=on/off/add/del/update）经 Jackson XmlMapper readTree
 * 合并进 Item 字段表, 与子元素字段同层平铺, 业务层(CatalogSubscriptionService)据此做增量路由;
 * 无属性时 Event 缺省为 null（2016 整目录形态）。
 * 纯 JUnit5, 不起 Spring。
 */
class ManScdpCodecNotifyTest {

    private final ManScdpCodec codec = new ManScdpCodec();

    @Test
    @DisplayName("#1 Notify/Catalog Item 属性 Event=add 落入 Item 字段表")
    void parseNotifyCatalogWithEventAttribute() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>5</SN>\r\n"
                + "<DeviceID>34020000002000000002</DeviceID>\r\n"
                + "<SumNum>1</SumNum>\r\n"
                + "<DeviceList Num=\"1\">\r\n"
                + "<Item Event=\"add\">\r\n"
                + "<DeviceID>34020000001320000006</DeviceID>\r\n"
                + "<Name>new-cam</Name>\r\n"
                + "<Parental>0</Parental>\r\n"
                + "<Status>ON</Status>\r\n"
                + "</Item>\r\n"
                + "</DeviceList>\r\n"
                + "</Notify>\r\n";

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals("Catalog", msg.getCmdType());
        assertEquals(1, msg.getItems().size());
        Map<String, String> item = msg.getItems().get(0);
        assertEquals("34020000001320000006", item.get("DeviceID"));
        assertEquals("add", item.get("Event"));
    }

    @Test
    @DisplayName("#2 无 Event 属性 → Event=null（2016 整目录形态）, 子元素字段不受影响")
    void parseNotifyCatalogWithoutEvent() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>6</SN>\r\n"
                + "<DeviceID>34020000002000000002</DeviceID>\r\n"
                + "<SumNum>1</SumNum>\r\n"
                + "<DeviceList Num=\"1\">\r\n"
                + "<Item>\r\n"
                + "<DeviceID>34020000001320000011</DeviceID>\r\n"
                + "<Name>lower-cam-02</Name>\r\n"
                + "<Status>ON</Status>\r\n"
                + "</Item>\r\n"
                + "</DeviceList>\r\n"
                + "</Notify>\r\n";

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals(1, msg.getItems().size());
        Map<String, String> item = msg.getItems().get(0);
        assertNull(item.get("Event"));
        assertEquals("34020000001320000011", item.get("DeviceID"));
        assertEquals("ON", item.get("Status"));
    }

    @Test
    @DisplayName("#3 buildCatalogNotify 构建 → parse 回读: DeviceID/Name/Status 全量往返一致")
    void buildCatalogNotifyRoundtrip() {
        String xml = codec.buildCatalogNotify("34020000002000000002", 7,
                List.of(Map.of("DeviceID", "34020000001320000011",
                        "Name", "lower-cam-01",
                        "Status", "ON")));

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals(1, msg.getItems().size());
        Map<String, String> item = msg.getItems().get(0);
        assertEquals("34020000001320000011", item.get("DeviceID"));
        assertEquals("lower-cam-01", item.get("Name"));
        assertEquals("ON", item.get("Status"));
    }

    @Test
    @DisplayName("#4 Event 属性值大小写原样保留(ADD) — 增量匹配由业务层大小写不敏感处理")
    void parseNotifyEventUppercase() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>8</SN>\r\n"
                + "<DeviceID>34020000002000000002</DeviceID>\r\n"
                + "<SumNum>1</SumNum>\r\n"
                + "<DeviceList Num=\"1\">\r\n"
                + "<Item Event=\"ADD\">\r\n"
                + "<DeviceID>34020000001320000006</DeviceID>\r\n"
                + "<Name>new-cam</Name>\r\n"
                + "</Item>\r\n"
                + "</DeviceList>\r\n"
                + "</Notify>\r\n";

        ManScdpMessage msg = codec.parse(xml);

        assertEquals(1, msg.getItems().size());
        assertEquals("ADD", msg.getItems().get(0).get("Event"));
    }

    @Test
    @DisplayName("#5 Notify/Alarm 字段解析: AlarmMethod/Type/Priority/Time/Description")
    void parseAlarmFields() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Alarm</CmdType>\r\n"
                + "<SN>9</SN>\r\n"
                + "<DeviceID>34020000001320000001</DeviceID>\r\n"
                + "<AlarmPriority>1</AlarmPriority>\r\n"
                + "<AlarmMethod>3</AlarmMethod>\r\n"
                + "<AlarmType>2</AlarmType>\r\n"
                + "<AlarmTime>2026-09-14T10:00:00</AlarmTime>\r\n"
                + "<AlarmDescription>test</AlarmDescription>\r\n"
                + "</Notify>\r\n";

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals("Alarm", msg.getCmdType());
        assertEquals(3, msg.getAlarmMethod());
        assertEquals(2, msg.getAlarmType());
        assertEquals(1, msg.getAlarmPriority());
        assertEquals("2026-09-14T10:00:00", msg.getAlarmTime());
        assertEquals("test", msg.getAlarmDescription());
    }

    @Test
    @DisplayName("#6 报警描述兼容双字段名: 无 AlarmDescription 时取 Information")
    void parseInformationFallback() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Alarm</CmdType>\r\n"
                + "<SN>10</SN>\r\n"
                + "<DeviceID>34020000001320000001</DeviceID>\r\n"
                + "<AlarmPriority>2</AlarmPriority>\r\n"
                + "<AlarmMethod>3</AlarmMethod>\r\n"
                + "<AlarmType>2</AlarmType>\r\n"
                + "<AlarmTime>2026-09-14T10:00:00</AlarmTime>\r\n"
                + "<Information>info-text</Information>\r\n"
                + "</Notify>\r\n";

        ManScdpMessage msg = codec.parse(xml);

        assertNull(msg.getItems());
        assertEquals("info-text", msg.getAlarmDescription());
    }

    @Test
    @DisplayName("#7 buildAlarmNotify 构建 → parse 回读: 报警五字段+DeviceID 全量往返一致")
    void buildAlarmNotifyRoundtrip() {
        String xml = codec.buildAlarmNotify("34020000001320000011", 11,
                "2026-09-14T10:00:00", 2, 1, 3, "sim-inject-alarm");

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals("Alarm", msg.getCmdType());
        assertEquals("34020000001320000011", msg.getDeviceId());
        assertEquals("2026-09-14T10:00:00", msg.getAlarmTime());
        assertEquals(2, msg.getAlarmType());
        assertEquals(1, msg.getAlarmPriority());
        assertEquals(3, msg.getAlarmMethod());
        assertEquals("sim-inject-alarm", msg.getAlarmDescription());
    }

    @Test
    @DisplayName("#8 buildAlarmNotify 缺省字段不输出: null 项省略仍可解析")
    void buildAlarmNotifyOmitsNullFields() {
        String xml = codec.buildAlarmNotify("34020000001320000011", 12, null, null, null, null, null);

        ManScdpMessage msg = codec.parse(xml);

        assertEquals("Notify", msg.getRootType());
        assertEquals("Alarm", msg.getCmdType());
        assertEquals("34020000001320000011", msg.getDeviceId());
        assertNull(msg.getAlarmTime());
        assertNull(msg.getAlarmType());
        assertNull(msg.getAlarmPriority());
        assertNull(msg.getAlarmMethod());
        assertNull(msg.getAlarmDescription());
    }
}
