package com.flowdesk.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskNotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskNotificationServiceApplication.class, args);
    }
}
