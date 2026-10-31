package com.yujian.vision.gb28181.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.yujian.vision.common.api.ErrorCode;
import com.yujian.vision.common.exception.BizException;
import com.yujian.vision.gb28181.message.ManScdpMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import org.springframework.stereotype.Component;

/**
 * MANSCDP+xml 编解码器（docs/01 §3.3）：
 * 入站宽松解析（禁 DTD/外部实体防 XXE，字段缺失容错）；出站按需构建查询报文。
 */
@Component
public class ManScdpCodec {

    private static final Pattern ROOT_TAG = Pattern.compile("<\\s*([A-Za-z][A-Za-z0-9_]*)");

    private final XmlMapper xmlMapper;

    public ManScdpCodec() {
        XMLInputFactory inputFactory = XMLInputFactory.newFactory();
        // XXE 防护（docs/10 §1）：禁 DTD 与外部实体
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        this.xmlMapper = new XmlMapper(new XmlFactory(inputFactory));
        this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 解析入站 MANSCDP 报文
     */
    public ManScdpMessage parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "空 MANSCDP 报文");
        }
        String rootType = "UNKNOWN";
        Matcher matcher = ROOT_TAG.matcher(xml);
        if (matcher.find()) {
            rootType = matcher.group(1);
        }
        try {
            JsonNode node = xmlMapper.readTree(xml);
            JsonNode sumNum = node.get("SumNum");
            ManScdpMessage message = ManScdpMessage.builder()
                    .rootType(rootType)
                    .cmdType(text(node, "CmdType"))
                    .sn(longOrNull(node, "SN"))
                    .deviceId(text(node, "DeviceID"))
                    .status(text(node, "Status"))
                    .ptzCmd(text(node, "PTZCmd"))
                    .sumNum(sumNum != null ? sumNum.asInt() : null)
                    .alarmMethod(intOrNull(node, "AlarmMethod"))
                    .alarmType(intOrNull(node, "AlarmType"))
                    .alarmPriority(intOrNull(node, "AlarmPriority"))
                    .alarmTime(text(node, "AlarmTime"))
                    .alarmDescription(firstText(node, "AlarmDescription", "Information"))
                    .items(readItems(node))
                    .build();
            return message;
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "MANSCDP 报文解析失败: " + e.getOriginalMessage());
        }
    }

    /**
     * 构建设备控制报文（Control/DeviceControl, 云台/预置位, docs/05 §6）
     */
    public String buildDeviceControlPtz(String deviceId, long sn, String ptzCmdHex) {
        String crlf = "\r\n";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + crlf
                + "<Control>" + crlf
                + "<CmdType>DeviceControl</CmdType>" + crlf
                + "<SN>" + sn + "</SN>" + crlf
                + "<DeviceID>" + deviceId + "</DeviceID>" + crlf
                + "<PTZCmd>" + ptzCmdHex + "</PTZCmd>" + crlf
                + "</Control>" + crlf;
    }

    /**
     * 构建目录查询报文（Query/Catalog，docs/05 §3）
     */
    public String buildCatalogQuery(String deviceId, long sn) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Query>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "</Query>\r\n";
    }

    /**
     * 构建预置位查询报文（Query/PresetQuery, GB/T 28181-2016 预置位检索）：应答为单包
     * RESPONSE/PresetList/Item（PresetID/PresetName, 见 parse 的 PresetList 分支）。
     */
    public String buildPresetQuery(String deviceId, long sn) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Query>\r\n"
                + "<CmdType>PresetQuery</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "</Query>\r\n";
    }

    /**
     * 构建报警订阅请求体（Query/Alarm, SUBSCRIBE Event: Alarm 的消息体, M3-B4）：
     * 不带过滤条件=全量订阅该设备的报警（订阅订阅方为平台、被订阅方为设备/下级）。
     */
    public String buildAlarmQuery(String deviceId, long sn) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Query>\r\n"
                + "<CmdType>Alarm</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "<StartAlarmPriority>1</StartAlarmPriority>\r\n"
                + "<EndAlarmPriority>4</EndAlarmPriority>\r\n"
                + "<AlarmMethod>0</AlarmMethod>\r\n"
                + "</Query>\r\n";
    }

    /**
     * 构建报警通知报文体（Notify/Alarm 平铺字段, M3-B4 阶段2 上级订阅制上报）：
     * DeviceID=报警源编码（虚拟编码替换已在业务层完成）, 五字段缺省项不输出。
     * 与设备侧上报（sim_device.alarm_notify_xml 同构）经 {@link #parse} 双向回读。
     */
    public String buildAlarmNotify(String deviceId, long sn, String alarmTime,
                                   Integer alarmType, Integer alarmPriority, Integer alarmMethod,
                                   String description) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(crlf)
                .append("<Notify>").append(crlf)
                .append("<CmdType>Alarm</CmdType>").append(crlf)
                .append("<SN>").append(sn).append("</SN>").append(crlf)
                .append("<DeviceID>").append(deviceId).append("</DeviceID>").append(crlf);
        if (alarmTime != null && !alarmTime.isBlank()) {
            sb.append("<AlarmTime>").append(alarmTime).append("</AlarmTime>").append(crlf);
        }
        if (alarmType != null) {
            sb.append("<AlarmType>").append(alarmType).append("</AlarmType>").append(crlf);
        }
        if (alarmPriority != null) {
            sb.append("<AlarmPriority>").append(alarmPriority).append("</AlarmPriority>").append(crlf);
        }
        if (alarmMethod != null) {
            sb.append("<AlarmMethod>").append(alarmMethod).append("</AlarmMethod>").append(crlf);
        }
        if (description != null && !description.isBlank()) {
            // 报警描述来自设备侧不可信输入, 同 buildCatalogBody 的目录条目转义口径
            sb.append("<AlarmDescription>").append(escapeXml(description))
                    .append("</AlarmDescription>").append(crlf);
        }
        sb.append("</Notify>").append(crlf);
        return sb.toString();
    }

    /**
     * 构建录像查询报文（Query/RecordInfo, docs/03 §6）：时间格式 yyyy-MM-dd'T'HH:mm:ss
     */
    public String buildRecordInfoQuery(String deviceId, long sn, String startTime, String endTime) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Query>\r\n"
                + "<CmdType>RecordInfo</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "<StartTime>" + startTime + "</StartTime>\r\n"
                + "<EndTime>" + endTime + "</EndTime>\r\n"
                + "</Query>\r\n";
    }

    /**
     * 构建回放控制报文（INFO 消息体, MANSRTSP, GB/T 28181-2016 附录; docs/07 §3）。
     * 取 XML 包装形式（Control/Cmd=MANSRTSP）, Content-Type 用
     * {@link com.yujian.vision.gb28181.message.ManScdpConstants#MANSRTSP_CONTENT_TYPE}。
     * 纯函数无状态故声明为 static, CRLF 风格 mirror buildDeviceControlPtz。
     *
     * @param seq   MANSRTSP 序号（调用方用 SipIds.nextSn() 生成）
     * @param action PAUSE / RESUME / SCALE
     * @param scale 仅 action=SCALE 时使用（0.25/0.5/1/2/4）, 其余忽略
     */
    public static String buildMansrtspControl(long seq, String action, java.math.BigDecimal scale) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(crlf)
                .append("<Control>").append(crlf)
                .append("<Cmd>MANSRTSP</Cmd>").append(crlf)
                .append("<MANSRTSP>").append(crlf)
                .append("<Version>1.0</Version>").append(crlf)
                .append("<Seq>").append(seq).append("</Seq>").append(crlf);
        if ("PAUSE".equals(action)) {
            sb.append("<Pause/>").append(crlf);
        } else if ("RESUME".equals(action)) {
            sb.append("<Resume/>").append(crlf);
        } else if ("SCALE".equals(action)) {
            sb.append("<Scale>").append(scale == null ? "1" : scale.toPlainString()).append("</Scale>").append(crlf);
        }
        sb.append("</MANSRTSP>").append(crlf)
                .append("</Control>").append(crlf);
        return sb.toString();
    }

    /**
     * 构建级联心跳报文（本平台作为下级向上级发 Notify/Keepalive, docs/05 §8）
     */
    public String buildKeepalive(String deviceId, long sn) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<Notify>\r\n"
                + "<CmdType>Keepalive</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "<Status>OK</Status>\r\n"
                + "</Notify>\r\n";
    }

    /**
     * 构建目录应答报文（RESPONSE/Catalog, 级联-下级目录上报, docs/08 §2.2）：
     * SN 全包回显上级查询 SN, SumNum=目录总数, DeviceList Num=本包条数。
     *
     * @param items   本包通道条目（字段名按国标: DeviceID/Name/Manufacturer/.../Status, 由业务层组装,
     *                虚拟编码替换已在业务层完成）
     * @param sumNum  目录总数（多包共享同一值）
     */
    public String buildCatalogResponse(String deviceId, long sn, int sumNum, List<Map<String, String>> items) {
        // root 用首字母大写 Response 而非全大写 RESPONSE: 主流生态(海康/大华等)消息分发按
        // "Query/Notify/Control/Response" 精确匹配, 全大写会被部分平台判 415(联调实测); 本平台
        // 解析侧按 CmdType 字段路由, 不依赖 root 大小写, 两种写法均可收
        return buildCatalogBody("Response", deviceId, sn, sumNum, items);
    }

    /**
     * 构建目录变化主动通知报文（Notify/Catalog, 2016 版以下级主动发 Catalog 通知实现,
     * docs/08 §2.2；上级兼容性联调确认）, 条目结构同 {@link #buildCatalogResponse}
     */
    public String buildCatalogNotify(String deviceId, long sn, List<Map<String, String>> items) {
        return buildCatalogBody("Notify", deviceId, sn, items.size(), items);
    }

    private String buildCatalogBody(String root, String deviceId, long sn, int sumNum,
                                    List<Map<String, String>> items) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(crlf)
                .append("<").append(root).append(">").append(crlf)
                .append("<CmdType>Catalog</CmdType>").append(crlf)
                .append("<SN>").append(sn).append("</SN>").append(crlf)
                .append("<DeviceID>").append(deviceId).append("</DeviceID>").append(crlf)
                .append("<SumNum>").append(sumNum).append("</SumNum>").append(crlf)
                .append("<DeviceList Num=\"").append(items == null ? 0 : items.size()).append("\">").append(crlf);
        if (items != null) {
            for (Map<String, String> item : items) {
                sb.append("<Item>").append(crlf);
                for (Map.Entry<String, String> f : item.entrySet()) {
                    if (f.getValue() != null) {
                        sb.append("<").append(f.getKey()).append(">")
                                .append(escapeXml(f.getValue()))
                                .append("</").append(f.getKey()).append(">").append(crlf);
                    }
                }
                sb.append("</Item>").append(crlf);
            }
        }
        sb.append("</DeviceList>").append(crlf)
                .append("</").append(root).append(">").append(crlf);
        return sb.toString();
    }
    /**
     * 预置位检索应答（Response/PresetQuery, 2026-09-26 上级转查补齐）：PresetList/Item,
     * Item 平铺 PresetID/PresetName（与解析侧 PresetList 分支对称, GB/T 28181-2016）。
     * 空列表回一包空 PresetList（Num=0）让上级立即收敛。
     */
    public String buildPresetResponse(String deviceId, long sn, List<Map<String, String>> items) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append(crlf)
                .append("<Response>").append(crlf)
                .append("<CmdType>PresetQuery</CmdType>").append(crlf)
                .append("<SN>").append(sn).append("</SN>").append(crlf)
                .append("<DeviceID>").append(deviceId).append("</DeviceID>").append(crlf)
                .append("<PresetList Num=\"").append(items == null ? 0 : items.size()).append("\">").append(crlf);
        if (items != null) {
            for (Map<String, String> item : items) {
                sb.append("<Item>").append(crlf);
                for (Map.Entry<String, String> f : item.entrySet()) {
                    if (f.getValue() != null) {
                        sb.append("<").append(f.getKey()).append(">")
                                .append(escapeXml(f.getValue()))
                                .append("</").append(f.getKey()).append(">").append(crlf);
                    }
                }
                sb.append("</Item>").append(crlf);
            }
        }
        sb.append("</PresetList>").append(crlf)
                .append("</Response>").append(crlf);
        return sb.toString();
    }


    /** 通道名等业务值内嵌 XML 的最小转义（& < >，与既有 build 方法的纯数字/编码字段不同, 名称来自设备侧不可信） */
    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }


    private List<Map<String, String>> readItems(JsonNode node) {
        // 目录应答用 DeviceList 包 Item, 录像检索应答(RecordInfo)用 RecordList, 预置位应答(PresetQuery)用
        // PresetList; 三者 Item 子节点结构一致(GB/T 28181-2016), 均按原始字段表平铺由业务层取用
        JsonNode list = node.get("DeviceList");
        if (list == null) {
            list = node.get("RecordList");
        }
        if (list == null) {
            list = node.get("PresetList");
        }
        if (list == null) {
            return null;
        }
        JsonNode firstItem = list.get("Item");
        List<JsonNode> itemNodes = new ArrayList<>();
        if (firstItem == null) {
            return null;
        }
        if (firstItem.isArray()) {
            firstItem.forEach(itemNodes::add);
        } else {
            itemNodes.add(firstItem);
        }
        List<Map<String, String>> items = new ArrayList<>(itemNodes.size());
        for (JsonNode item : itemNodes) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> f : item.properties()) {
                String value = f.getValue() == null ? null : f.getValue().asText();
                if (value != null && !value.isBlank()) {
                    fields.put(f.getKey(), value.trim());
                }
            }
            items.add(fields);
        }
        return items;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null ? v.asText() : null;
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null ? v.asLong() : null;
    }

    /**
     * 整数字段解析（AlarmMethod/AlarmType/AlarmPriority, M3-B4）: XmlMapper readTree 对元素文本
     * 产出 TextNode（canConvertToInt()=false）, 须按文本解析; 数字节点直接取值, 非数字容错为 null。
     */
    private Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        if (v.isTextual()) {
            try {
                return Integer.parseInt(v.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 报警描述兼容双字段名（AlarmDescription 主/Information 兼容部分厂商） */
    private String firstText(JsonNode node, String primary, String fallback) {
        String v = text(node, primary);
        return v != null ? v : text(node, fallback);
    }
}
