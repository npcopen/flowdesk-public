package com.flowdesk.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.flowdesk.workflow", "com.flowdesk.common"})
public class FlowDeskWorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskWorkflowServiceApplication.class, args);
    }
}
