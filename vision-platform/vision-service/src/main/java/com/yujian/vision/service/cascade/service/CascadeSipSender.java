package com.yujian.vision.service.cascade.service;

import com.yujian.vision.gb28181.message.ManScdpConstants;
import com.yujian.vision.service.sip.config.GbPlatformProperties;
import com.yujian.vision.sip.transport.SipIds;
import com.yujian.vision.sip.transport.SipMessageContext;
import com.yujian.vision.sip.transport.SipTransportProvider;
import com.yujian.vision.service.cascade.entity.GbCascadeUpperEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 级联出站信令薄封装：统一"选栈 + From=本平台级联编码 + 目标=上级地址"三件事
 * （docs/08 §2.1, 发送范式与 CatalogSyncService 一致）。
 */
@Component
@RequiredArgsConstructor
public class CascadeSipSender {

    private final SipTransportProvider transport;
    private final GbPlatformProperties platform;
    private final CascadeOutboundCodec outboundCodec;

    /** 出站栈：按上级要求的传输协议选, 无匹配回平台默认栈 */
    public String stackFor(GbCascadeUpperEntity upper) {
        String stackId = transport.stackIdForTransport(upper.getTransport());
        return stackId != null ? stackId : platform.getStackId();
    }

    /** Digest 认证的 uri 参数：与 transport.send 构造的 Request-URI 保持一致 */
    public static String requestUri(GbCascadeUpperEntity upper) {
        return "sip:" + upper.getUpperDeviceId() + "@" + upper.getUpperIp() + ":" + upper.getUpperPort();
    }

    /**
     * 发 REGISTER（级联注册/刷新）。authorization/expires 可空（首轮不带鉴权）。
     */
    public void sendRegister(GbCascadeUpperEntity upper, String stackId, String callId, long sn,
                             String authorization, Integer expires) {
        transport.send(SipMessageContext.builder()
                .stackId(stackId).method("REGISTER")
                .from(upper.getLocalDeviceId() + "@" + platform.getDomain())
                .to(upper.getUpperDeviceId() + "@" + platform.getDomain())
                .callId(callId)
                .sn(sn)
                .authorization(authorization)
                .expires(expires)
                .targetHost(upper.getUpperIp()).targetPort(upper.getUpperPort())
                .build());
    }

    /**
     * 发 MESSAGE（MANSCDP+xml, 新事务）。返回本次事务 CallID（应答关联用）。
     */
    public String sendMessage(GbCascadeUpperEntity upper, String body, long sn) {
        String callId = SipIds.newCallId();
        transport.send(SipMessageContext.builder()
                .stackId(stackFor(upper)).method("MESSAGE")
                .from(upper.getLocalDeviceId() + "@" + platform.getDomain())
                .to(upper.getUpperDeviceId() + "@" + platform.getDomain())
                .callId(callId)
                .sn(sn)
                .body(outboundCodec.body(body))
                .contentType(ManScdpConstants.CONTENT_TYPE)
                .contentCharset(outboundCodec.tuned() ? outboundCodec.charset() : null)
                .targetHost(upper.getUpperIp()).targetPort(upper.getUpperPort())
                .build());
        return callId;
    }
}
