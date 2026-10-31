package com.yujian.vision.gb28181.spi;

/**
 * GB28181 协议适配器 SPI（docs/01 §3.2）：每个国标版本一个实现（2016/2022）。
 *
 * <p>共性下沉：MANSCDP XML 通用骨架解析、字段容错、编码校验由基类完成；
 * 子类只覆写版本差异消息（目录订阅/分页、语音对讲流程、新增管理指令）。</p>
 *
 * <p>按设备选适配器：device.protocol_version 决定，业务层无感知。</p>
 */
public interface ProtocolAdapter {

    /** 协议版本标识："2016" / "2022" */
    String version();

    /** 是否支持某协议能力（能力协商：手动配置 &gt; 注册探测 &gt; 域默认） */
    boolean supports(ProtocolCapability capability);

    /**
     * 构建 INVITE SDP（实况/回放/对讲共用骨架，模板见 docs/04 §4）
     */
    String buildInviteSdp(MediaOffer offer);

    // 设计预留（docs/01 §3.2）: 本 SPI 无实现, 2016 侧编解码实际由 codec/ManScdpCodec
    // （parse/buildCatalogQuery/buildRecordInfoQuery/buildDeviceControlPtz）与 control/PtzCmdBuilder 承担;
    // 2022 版本适配器实施时在此补齐 decode(MessageContext) 与 encode* 各方法。
}
