package com.flowdesk.notification.event;

import com.flowdesk.notification.service.NotificationRecoveryService;
import com.flowdesk.notification.service.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in wiring for Notification's strict main/DLQ consumers. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRocketMqProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.notification.entry", name = "enabled",
        havingValue = "true")
public class NotificationConsumerConfiguration {
    @Bean
    NotificationRocketMqConsumers notificationRocketMqConsumers(
            NotificationRocketMqProperties properties,
            NotificationService service,
            NotificationRecoveryService recovery) {
        return new NotificationRocketMqConsumers(properties,
                (NotificationRocketMqConsumers.NotificationServiceAdapter) service::consume,
                (NotificationRocketMqConsumers.NotificationDlqAdapter) service::acceptDlq);
    }
}
