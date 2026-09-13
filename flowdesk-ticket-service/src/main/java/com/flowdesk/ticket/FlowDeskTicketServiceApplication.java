package com.flowdesk.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.flowdesk")
public class FlowDeskTicketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowDeskTicketServiceApplication.class, args);
    }
}
