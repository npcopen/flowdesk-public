package com.flowdesk.workflow.notification;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 为 HTTP 审批命令和按需启用的消息消费入口装配同一通知意图写入器。
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkflowNotificationIntentConfiguration.NotificationEnabled.class)
public class WorkflowNotificationIntentConfiguration {
    /**
     * 装配事务消息写入器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    WorkflowNotificationOutboxWriter workflowNotificationOutboxWriter(
            WorkflowNotificationIntentMapper mapper) {
        return new WorkflowNotificationOutboxWriter((tenantId, intent) ->
                mapper.insertNotificationOutbox(tenantId, intent.outboxId(), intent.messageId(),
                        intent.publicationKey(), intent.businessIntentKey(), intent.sourceRefId(),
                        1, intent.bodyBytes(), intent.bodySha256(), intent.correlationId(),
                        intent.causationId(), intent.occurredAt()));
    }

    /**
     * 无 Web、仅健康检查的启动模式允许没有 DataSource，故仅在依赖齐备时装配。
     */
    static final class NotificationEnabled extends AnyNestedCondition {
        /**
         * 创建异常或专用实现并保留传入原因，供调用方分类处理。
         */
        NotificationEnabled() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        /**
         * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
         */
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        static class HttpCommands {
        }

        /**
         * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
         */
        @ConditionalOnProperty(prefix = "flowdesk.workflow.start.entry", name = "enabled",
                havingValue = "true")
        static class BrokerEntry {
        }
    }
}
