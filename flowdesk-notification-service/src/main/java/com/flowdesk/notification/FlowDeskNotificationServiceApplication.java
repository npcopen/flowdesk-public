package com.flowdesk.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 通知服务启动入口，加载用户通知 API、消息消费及人工恢复组件。 */
@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskNotificationServiceApplication {

    /**
     * 启动 Spring Boot 应用并装配服务组件，命令行参数交由框架继续解析。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskNotificationServiceApplication.class, args);
    }
}
