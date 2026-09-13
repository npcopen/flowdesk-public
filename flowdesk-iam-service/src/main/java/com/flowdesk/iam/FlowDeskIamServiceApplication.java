package com.flowdesk.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskIamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskIamServiceApplication.class, args);
    }
}
