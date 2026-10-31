package com.yujian.vision.web.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class WebUiConfigTest {

    private static MockHttpServletRequest req(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    @Test
    void spaRoutesAndAssetsArePublic() {
        assertTrue(WebUiConfig.isWebUiRequest(req("GET", "/")));
        assertTrue(WebUiConfig.isWebUiRequest(req("GET", "/login")));
        assertTrue(WebUiConfig.isWebUiRequest(req("GET", "/access/device")));
        assertTrue(WebUiConfig.isWebUiRequest(req("GET", "/assets/index-abc.js")));
        assertTrue(WebUiConfig.isWebUiRequest(req("HEAD", "/favicon.svg")));
    }

    @Test
    void backendPathsStayAuthenticated() {
        assertFalse(WebUiConfig.isWebUiRequest(req("GET", "/api/health")));
        assertFalse(WebUiConfig.isWebUiRequest(req("GET", "/openapi/v1/play")));
        assertFalse(WebUiConfig.isWebUiRequest(req("GET", "/ws/api")));
        assertFalse(WebUiConfig.isWebUiRequest(req("GET", "/actuator/prometheus")));
        assertFalse(WebUiConfig.isWebUiRequest(req("POST", "/login")));
    }
}
