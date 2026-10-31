package com.yujian.vision.sip.transport;

/**
 * 入站 SIP 事件监听器：transport 层把栈回调转为 {@link SipEvent} 后投递到此。
 * 实现方注意：onEvent 在 transport 线程池中执行，内部只做分发，阻塞 IO 下沉业务线程池。
 */
public interface SipEventListener {

    void onEvent(SipEvent event);
}
