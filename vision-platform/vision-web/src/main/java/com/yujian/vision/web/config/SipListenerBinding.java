package com.yujian.vision.web.config;

import com.yujian.vision.service.sip.SipEventRouter;
import com.yujian.vision.sip.transport.SipTransportProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动完成后把 SipEventRouter 绑定为 SIP 传输层的入站监听器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SipListenerBinding implements ApplicationRunner {

    private final SipTransportProvider transport;
    private final SipEventRouter router;

    @Override
    public void run(ApplicationArguments args) {
        transport.setListener(router);
        log.info("SIP 入站监听器已绑定: {}", router.getClass().getSimpleName());
    }
}
