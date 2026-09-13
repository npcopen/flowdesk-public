package com.flowdesk.ticket.config;

import com.flowdesk.ticket.iam.TicketIamClientProperties;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/** Ticket service local configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TicketCursorTokenService.CursorProperties.class, TicketIamClientProperties.class})
public class TicketConfiguration {

    @org.springframework.context.annotation.Bean
    public RestClient.Builder flowDeskTicketRestClientBuilder() {
        return RestClient.builder();
    }

    @org.springframework.context.annotation.Bean
    public Clock flowDeskTicketClock() {
        return Clock.systemUTC();
    }
}
