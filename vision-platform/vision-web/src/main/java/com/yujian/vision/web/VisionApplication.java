package com.yujian.vision.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * YuJianVision 视频监控平台启动入口
 */
@SpringBootApplication(scanBasePackages = "com.yujian.vision")
@MapperScan("com.yujian.vision.**.mapper")
@EnableScheduling
public class VisionApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisionApplication.class, args);
    }
}
