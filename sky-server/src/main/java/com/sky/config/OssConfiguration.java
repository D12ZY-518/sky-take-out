package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 配置类
 */
@Configuration
@Slf4j
public class OssConfiguration {

    @Value("${sky.alioss.endpoint}")
    private String endpoint;

    @Value("${sky.alioss.access-key-id}")
    private String accessKeyId;

    @Value("${sky.alioss.access-key-secret}")
    private String accessKeySecret;

    @Value("${sky.alioss.bucket-name}")
    private String bucketName;

    /**
     * 应用启动时打印 OSS 配置信息
     */
    @Bean
    public CommandLineRunner printOssInfo() {
        return args -> {
            log.info("===========================================");
            log.info("OSS 配置信息:");
            log.info("Endpoint: {}", endpoint);
            log.info("Bucket: {}", bucketName);
            log.info("访问地址：https://{}.{}", bucketName, endpoint.replaceFirst("oss-", ""));
            log.info("⚠️ 请确保已在阿里云 OSS 控制台配置 CORS 规则！");
            log.info("===========================================");
        };
    }
}
