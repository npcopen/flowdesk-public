package com.flowdesk.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 工单服务的 Spring Boot 启动入口，启用本模块的组件扫描和自动配置。 */
@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskTicketServiceApplication {

    /**
     * 启动工单服务，装配 HTTP、数据库、消息消费与后台任务组件。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskTicketServiceApplication.class, args);
    }
}
