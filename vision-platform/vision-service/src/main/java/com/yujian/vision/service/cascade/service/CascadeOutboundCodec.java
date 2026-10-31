package com.yujian.vision.service.cascade.service;

import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 级联出站报文编码适配（2026-09-25 LiveGBS 联调缺陷）：国标公安系上级（LiveGBS 等）
 * 按主管网惯例以 GBK/GB2312 码制解析目录/报警, 而平台出站为 UTF-8（声明与字节一致）——
 * 对端硬按 GB 系解即中文乱码。本类把出站 MANSCDP 统一到配置编码：
 * body 的 XML 声明改写 + SipMessageContext.contentCharset（Content-Type charset 参数,
 * jain-sip 序列化按其字节化）。标准 jain-sip 对端（如部分第三方平台）按 charset 参数解码不受影响。
 *
 * <p>默认 GBK 而非 GB2312（JDK17 实测 GB2312=EUC_CN 严格子集, 生僻字变 ?）：
 * GBK 向下兼容且为 LiveGBS 实际码制（其出站即 GBK 扩展字）, 平台入站侧同场景亦按
 * 超集 GBK 解码（JainSipTransportProvider 声明嗅探口径）, 出入对称。配置
 * {@code vision.cascade.outbound-charset}, 配 UTF-8 即关闭适配。</p>
 *
 * <p>仅作用于级联出站面（CascadeSipSender/CascadeAlarmService）, 设备接入面保持 UTF-8
 * 不动（已验证行为不回退）。</p>
 */
@Component
public class CascadeOutboundCodec {

    private final String charset;

    public CascadeOutboundCodec(
            @Value("${vision.cascade.outbound-charset:GBK}") String charset) {
        // null/空白跟随生产默认 GBK（显式配 UTF-8 才是关闭适配）, 语义单一不困惑
        String name = charset == null || charset.isBlank() ? "GBK" : charset.trim();
        // fail-fast（P2-4 评审）: 非法编码名若放到运行期, jain-sip 每条报文字节化时才炸
        // (InternalErrorHandler RuntimeException) 且无降级; 启动期暴露最清晰
        if (!Charset.isSupported(name)) {
            throw new UnsupportedCharsetException(
                    "vision.cascade.outbound-charset 配置的编码不被 JVM 支持: " + name);
        }
        this.charset = name;
    }

    /** 出站字符集名（XML encoding 声明与 Content-Type charset 参数共用） */
    public String charset() {
        return charset;
    }

    /** 适配是否生效（非 UTF-8 时为 true） */
    public boolean tuned() {
        return !"UTF-8".equalsIgnoreCase(charset);
    }

    /**
     * 规范化出站 XML body：把 ManScdpCodec 固定写入的 {@code encoding="UTF-8"} 声明
     * 改写为出站编码（字节化由 jain-sip 按 Content-Type charset 完成, 这里只改声明）。
     * 无声明/已一致时原样返回。
     */
    public String body(String xml) {
        if (!tuned() || xml == null || xml.isEmpty()) {
            return xml;
        }
        return xml.replace("encoding=\"UTF-8\"", "encoding=\"" + charset + "\"");
    }
}
