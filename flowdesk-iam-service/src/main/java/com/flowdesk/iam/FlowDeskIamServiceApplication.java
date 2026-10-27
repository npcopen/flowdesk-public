package com.flowdesk.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 身份与权限服务的 Spring Boot 启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskIamServiceApplication {

    /**
     * 启动当前 Spring Boot 服务，加载模块配置并由容器组装 HTTP、数据库及异步处理组件。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskIamServiceApplication.class, args);
    }
}
