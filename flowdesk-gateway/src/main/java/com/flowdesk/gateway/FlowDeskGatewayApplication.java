package com.flowdesk.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskGatewayApplication.class, args);
    }
}
