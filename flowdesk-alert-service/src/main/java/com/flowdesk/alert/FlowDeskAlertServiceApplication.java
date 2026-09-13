package com.flowdesk.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskAlertServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskAlertServiceApplication.class, args);
    }
}
