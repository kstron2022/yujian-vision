package com.yujian.vision.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（springdoc）配置：swagger-ui 路径 /swagger-ui.html
 * 预置 Bearer 认证方案，M1 任务9 JWT 落地后接口自动关联
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI visionOpenApi() {
        final String schemeName = "Bearer";
        return new OpenAPI()
                .info(new Info()
                        .title("YuJianVision 平台 API")
                        .description("GB28181 视频监控平台接口文档，契约见 docs/03-REST-API设计.md")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
