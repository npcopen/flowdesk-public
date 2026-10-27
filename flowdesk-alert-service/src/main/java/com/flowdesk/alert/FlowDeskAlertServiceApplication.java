package com.flowdesk.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 告警服务的 Spring Boot 启动入口，同时扫描共享基础组件与告警模块。
 * 业务阅读从 api/WebhookController 或 api/AlertController 开始；
 * 异步消息入口位于 event 包，人工恢复入口位于 recovery 包。
 */
@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskAlertServiceApplication {

    /**
     * 启动当前 Spring Boot 服务，加载模块配置并由容器组装 HTTP、数据库及异步处理组件。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskAlertServiceApplication.class, args);
    }
}
