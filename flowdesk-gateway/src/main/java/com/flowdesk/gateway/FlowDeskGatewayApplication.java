package com.flowdesk.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FlowDesk 网关启动入口，加载路由、安全过滤器和前端静态资源服务。
 */
@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskGatewayApplication {

    /**
     * 启动 Spring Boot 网关并加载路由、服务发现及边缘安全过滤器。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskGatewayApplication.class, args);
    }
}
