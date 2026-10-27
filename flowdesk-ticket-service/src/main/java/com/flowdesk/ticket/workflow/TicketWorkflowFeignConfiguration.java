package com.flowdesk.ticket.workflow;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 仅由 TicketWorkflowFeignClient 引入，不参与全局组件扫描。
 * 保留可配置的 2 秒连接、3 秒读取超时；禁用重试、日志和重定向。
 */
public class TicketWorkflowFeignConfiguration {

    /**
     * 装配工作流访问的 ticketWorkflowRequestOptions 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Request.Options ticketWorkflowRequestOptions(TicketWorkflowClientProperties properties) {
        return new Request.Options(
                properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS, false);
    }

    /**
     * 装配工作流访问的 ticketWorkflowRetryer 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Retryer ticketWorkflowRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 装配工作流访问的 ticketWorkflowLoggerLevel 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Logger.Level ticketWorkflowLoggerLevel() {
        return Logger.Level.NONE;
    }
}
