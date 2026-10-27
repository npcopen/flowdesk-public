package com.flowdesk.notification.config;

import com.flowdesk.notification.event.NotificationEnvelopeGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 提供通知服务共享的 UTC 时钟和严格消息契约校验器。 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    /** 所有通知持久化时间使用同一个 UTC 时钟。 */
    @Bean
    public Clock flowDeskNotificationClock() {
        return Clock.systemUTC();
    }

    /**
     * 装配消息或迁移校验器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    public NotificationEnvelopeGuard notificationEnvelopeGuard() {
        return new NotificationEnvelopeGuard();
    }
}
