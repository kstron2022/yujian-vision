package com.yujian.vision.web.config;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 内嵌前端：构建时把 web-ui/dist 打进 classpath:/static/，平台直接从 8082 提供页面。
 * <p>默认自动检测（存在 static/index.html 即启用），可用 vision.webui.embedded=false 关闭；
 * 关闭时行为与前后端分离部署完全一致，前端由 Nginx 托管。</p>
 * <p>SPA 深链（/login、/access/device 等）由资源解析器回落到 index.html；
 * /api、/ws 等后端前缀不做回落，避免把不存在的接口请求伪装成页面。</p>
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private static final String INDEX_LOCATION = "classpath:/static/index.html";
    private static final String[] BACKEND_PREFIXES = {"/api", "/openapi", "/ws", "/actuator", "/v3", "/swagger-ui"};

    private final boolean enabled;

    public WebUiConfig(Environment env) {
        this.enabled = env.getProperty("vision.webui.embedded", Boolean.class, indexPresent());
    }

    /** 构建产物里是否带前端首页。 */
    public static boolean indexPresent() {
        return new ClassPathResource("static/index.html").exists();
    }

    /** 安全放行范围：内嵌前端下的 GET/HEAD 页面与静态资源，后端前缀除外。 */
    public static boolean isWebUiRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        for (String prefix : BACKEND_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!enabled) {
            return;
        }
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                            return index();
                        }
                        for (String prefix : BACKEND_PREFIXES) {
                            String p = prefix.startsWith("/") ? prefix.substring(1) : prefix;
                            if (resourcePath.equals(p) || resourcePath.startsWith(p + "/")) {
                                return null;
                            }
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 无扩展名的前端路由回落到首页；缺失的 js/css 等资源保持 404
                        return resourcePath.indexOf('.') < 0 ? index() : null;
                    }

                    private Resource index() {
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
