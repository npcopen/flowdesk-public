package com.flowdesk.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
  * 使用真实开发 Nacos 配置验证公共与服务文档加载，并检查必需配置缺失时的启动拒绝。
  */
 @EnabledIfEnvironmentVariable(named = "FLOWDESK_NACOS_SMOKE_TEST", matches = "true")
class NacosConfigurationSmokeTest {

    /**
     * 验证从开发命名空间实际加载公共及网关 Nacos 文档，并获得预期服务路由配置。
     */
    @Test
    void loadsCommonAndServiceConfigurationFromTheDevelopmentNamespace() {
        try (ConfigurableApplicationContext context = application().run()) {
            ConfigurableEnvironment environment = context.getEnvironment();

            assertThat(environment.getActiveProfiles()).containsExactly("dev");
            assertThat(environment.getProperty("flowdesk.configuration.environment"))
                    .isEqualTo("dev");
            assertThat(environment.getProperty("spring.cloud.nacos.config.namespace"))
                    .isEqualTo("flowdesk-dev");
            assertThat(environment.getProperty("spring.cloud.nacos.discovery.namespace"))
                    .isEqualTo("flowdesk-dev");
            assertThat(environment.getProperty("spring.cloud.nacos.config.group"))
                    .isEqualTo(environment.getProperty("spring.cloud.nacos.discovery.group"));
            assertThat(environment.getProperty("spring.cloud.nacos.config.refresh-enabled", Boolean.class))
                    .isFalse();

            List<String> sourceNames = new ArrayList<>();
            for (PropertySource<?> source : environment.getPropertySources()) {
                sourceNames.add(source.getName());
            }
            assertThat(sourceNames).anyMatch(name -> name.contains("flowdesk-common.yaml"));
            assertThat(sourceNames).anyMatch(name -> name.contains("flowdesk-gateway.yaml"));
            assertThat(environment.getProperty("flowdesk.gateway.routes.iam"))
                    .isEqualTo("lb://flowdesk-iam-service");
        }
    }

    /**
     * 验证缺少必需服务配置时在应用启动前失败，不被本地默认值掩盖。
     */
    @Test
    void refusesToStartWhenTheRequiredServiceConfigurationIsMissing() {
        String missingService = "flowdesk-smoke-missing-" + UUID.randomUUID();

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = application().run(
                    "--spring.application.name=" + missingService)) {
                // No beans are needed: configuration must be rejected before startup.
            }
        }).satisfies(failure -> assertThat(causalMessages(failure))
                .contains("Missing required Nacos configuration"));
    }

    /**
     * 创建仅加载配置的无 Web Spring 应用并选择 dev 环境，供 Nacos 实际启动烟雾验证。
     */
    private static SpringApplication application() {
        SpringApplication application = new SpringApplication(ConfigurationOnly.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("dev");
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    /**
     * 收集异常因果链消息，便于断言启动失败确实由必需远程配置缺失触发。
     */
    private static String causalMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    /**
     * Nacos 烟雾测试的最小配置入口，仅执行环境与配置加载，不启动 Web 处理或业务组件。
     */
    @Configuration(proxyBeanMethods = false)
    static class ConfigurationOnly {
    }
}
