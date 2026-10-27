package com.flowdesk.ticket.config;

import com.flowdesk.ticket.iam.TicketIamClientProperties;
import com.flowdesk.ticket.iam.IamEligibilityFeignClient;
import com.flowdesk.ticket.service.TicketCursorTokenService;
import com.flowdesk.ticket.workflow.TicketWorkflowClientProperties;
import com.flowdesk.ticket.workflow.TicketWorkflowFeignClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.openfeign.EnableFeignClients;

import java.time.Clock;

/** 工单服务的显式 Feign 客户端、配置属性及可替换的统一时钟。 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {IamEligibilityFeignClient.class, TicketWorkflowFeignClient.class})
@EnableConfigurationProperties({TicketCursorTokenService.CursorProperties.class,
        TicketIamClientProperties.class, TicketWorkflowClientProperties.class})
public class TicketConfiguration {

    /**
     * 提供统一的 UTC 时钟，业务与测试通过同一时间入口控制时间边界。
     */
    @org.springframework.context.annotation.Bean
    public Clock flowDeskTicketClock() {
        return Clock.systemUTC();
    }
}
