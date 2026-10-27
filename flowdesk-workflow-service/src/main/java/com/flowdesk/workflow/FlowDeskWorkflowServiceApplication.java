package com.flowdesk.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工作流与审批服务的 Spring Boot 启动入口。
 */
@SpringBootApplication(scanBasePackages = {"com.flowdesk.workflow", "com.flowdesk.common"})
public class FlowDeskWorkflowServiceApplication {

    /**
     * 启动 Spring Boot 应用并装配服务组件，命令行参数交由框架继续解析。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlowDeskWorkflowServiceApplication.class, args);
    }
}
