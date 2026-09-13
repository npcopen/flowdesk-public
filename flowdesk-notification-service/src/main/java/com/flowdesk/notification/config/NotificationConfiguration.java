package com.flowdesk.notification.config;

import com.flowdesk.notification.event.NotificationEnvelopeGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Shared Notification-service infrastructure values. */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    /** All persisted Notification timestamps use one UTC wall clock. */
    @Bean
    public Clock flowDeskNotificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public NotificationEnvelopeGuard notificationEnvelopeGuard() {
        return new NotificationEnvelopeGuard();
    }
}
