package com.yujian.vision.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yujian.vision.service.monitor.AlertNoticeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * AlertWebhookController 单测（评审 B4-#5）：鉴权恒定时间比对/缺失/空白/错误全 401,
 * 正确 token 放行转 AlertNoticeService, 超限 body 413 不进处理。
 */
class AlertWebhookControllerTest {

    private static final String TOKEN = "tok123";

    private final AlertNoticeService alerts = mock(AlertNoticeService.class);
    private final AlertWebhookController controller = new AlertWebhookController(alerts, TOKEN);

    private ResponseEntity<?> post(String token, String contentLength, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (contentLength != null) {
            request.addHeader("Content-Length", contentLength);
        }
        return controller.handle(token, request, body);
    }

    @Test
    @DisplayName("token 缺失/空白/错误 → 401 且不进处理")
    void authFailuresReturn401() {
        assertEquals(HttpStatus.UNAUTHORIZED, post(null, "2", "{}").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, post("   ", "2", "{}").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, post("wrong", "2", "{}").getStatusCode());
        verify(alerts, never()).handle(anyString());
    }

    @Test
    @DisplayName("正确 token → 200 并转 AlertNoticeService.handle")
    void validTokenProcessesBody() {
        assertEquals(HttpStatus.OK, post(TOKEN, "5", "{}").getStatusCode());
        verify(alerts).handle("{}");
    }

    @Test
    @DisplayName("Content-Length 超限 → 413 且不进处理")
    void oversizedBodyRejected() {
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, post(TOKEN, "999999", "x").getStatusCode());
        verify(alerts, never()).handle(anyString());
    }

    @Test
    @DisplayName("非法 Content-Length 头按未知处理, 正常鉴权放行")
    void malformedContentLengthIgnored() {
        assertEquals(HttpStatus.OK, post(TOKEN, "abc", "{}").getStatusCode());
        verify(alerts).handle("{}");
    }
}
