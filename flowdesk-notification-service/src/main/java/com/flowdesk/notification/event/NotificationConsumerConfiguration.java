package com.flowdesk.notification.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按开关装配通知主队列和死信消费者，复用统一的事务消费服务。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRocketMqProperties.class)
@ConditionalOnProperty(prefix = "flowdesk.notification.entry", name = "enabled",
        havingValue = "true")
public class NotificationConsumerConfiguration {
    /**
     * 装配消息消费者，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    NotificationRocketMqConsumers notificationRocketMqConsumers(
            NotificationRocketMqProperties properties,
            NotificationConsumerService service) {
        return new NotificationRocketMqConsumers(properties,
                (NotificationRocketMqConsumers.NotificationServiceAdapter) service::consume,
                (NotificationRocketMqConsumers.NotificationDlqAdapter) service::acceptDlq);
    }
}
