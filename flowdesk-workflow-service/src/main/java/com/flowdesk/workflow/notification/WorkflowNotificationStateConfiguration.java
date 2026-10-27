package com.flowdesk.workflow.notification;

import com.flowdesk.workflow.service.WorkflowNotificationOutboxService;
import com.flowdesk.workflow.service.impl.WorkflowNotificationOutboxServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 工作流配置与组件装配入口，按声明的开关、连接和依赖约束建立一致的运行链路。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkflowNotificationStateConfiguration {
    /**
     * 装配业务服务，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    WorkflowNotificationOutboxService workflowNotificationOutboxService(
            WorkflowNotificationOutboxMapper mapper, PlatformTransactionManager manager) {
        return new WorkflowNotificationOutboxServiceImpl(mapper, manager);
    }
}
