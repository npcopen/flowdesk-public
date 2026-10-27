package com.flowdesk.workflow.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 通知意图条件装配测试，验证无头启动和 HTTP 命令复用同一写入器且正确遵循入口开关。
 */
class WorkflowNotificationIntentConfigurationTest {
    /**
     * 验证禁用启动消费入口时无头健康启动不依赖数据库，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void headlessHealthStartupNeedsNoDatabaseWhenStartEntryIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkflowNotificationIntentConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WorkflowNotificationOutboxWriter.class);
                });
    }

    /**
     * 验证无头启动入口复用共享通知写入器，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void headlessStartEntryUsesTheSharedWriter() {
        new ApplicationContextRunner()
                .withUserConfiguration(WorkflowNotificationIntentConfiguration.class)
                .withPropertyValues("flowdesk.workflow.start.entry.enabled=true")
                .withBean(WorkflowNotificationIntentMapper.class, () -> mock(WorkflowNotificationIntentMapper.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkflowNotificationOutboxWriter.class);
                });
    }

    /**
     * 验证两种启动入口开关下 HTTP 命令均复用同一个写入器，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void httpCommandsUseOneWriterWithEitherStartEntrySetting() {
        for (String enabled : new String[]{"false", "true"}) {
            new WebApplicationContextRunner()
                    .withUserConfiguration(WorkflowNotificationIntentConfiguration.class)
                    .withPropertyValues("flowdesk.workflow.start.entry.enabled=" + enabled)
                    .withBean(WorkflowNotificationIntentMapper.class, () -> mock(WorkflowNotificationIntentMapper.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(WorkflowNotificationOutboxWriter.class);
                    });
        }
    }
}
