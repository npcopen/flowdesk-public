package com.flowdesk.ticket.iam;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 仅由 IamEligibilityFeignClient 引入的客户端配置。
 *
 * <p>这里不加 Configuration 或 Component 注解，避免被全项目组件扫描加载为全局配置。
 * 超时沿用已有配置，失败不自动重试，不输出请求头或正文；禁用重定向避免把服务令牌转发到其他地址。
 */
public class TicketIamFeignConfiguration {

    /**
     * 装配IAM 资格的 ticketIamRequestOptions 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Request.Options ticketIamRequestOptions(TicketIamClientProperties properties) {
        return new Request.Options(
                properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS, false);
    }

    /**
     * 装配IAM 资格的 ticketIamRetryer 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Retryer ticketIamRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 装配IAM 资格的 ticketIamLoggerLevel 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    public Logger.Level ticketIamLoggerLevel() {
        return Logger.Level.NONE;
    }
}
