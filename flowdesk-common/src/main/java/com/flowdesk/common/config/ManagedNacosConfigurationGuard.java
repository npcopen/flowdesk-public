package com.flowdesk.common.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * 在配置数据加载后校验受管环境边界，检查必需的 Nacos 远程标记以防缺失配置仍继续启动。
 */
public final class ManagedNacosConfigurationGuard implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> MANAGED_PROFILES = Set.of("dev", "test", "prod");
    private static final String GROUP = "FLOWDESK_GROUP";

    /**
     * 将检查安排在配置数据加载之后，确保能够核验 Nacos 远程属性源。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 在受管环境核对唯一 profile、命名空间、分组及远程文档标记；配置漂移时立即阻止启动。
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .filter(MANAGED_PROFILES::contains).toList();
        if (profiles.isEmpty()) {
            if (Arrays.stream(environment.getDefaultProfiles()).anyMatch(MANAGED_PROFILES::contains)) {
                throw invalid("Select dev, test, or prod explicitly through spring.profiles.active");
            }
            return;
        }
        if (profiles.size() != 1) {
            throw invalid("Exactly one of dev, test, or prod must be active");
        }

        String profile = profiles.get(0);
        String namespace = "flowdesk-" + profile;
        requireProperty(environment, "spring.cloud.nacos.config.enabled", "true");
        requireProperty(environment, "spring.cloud.nacos.config.namespace", namespace);
        requireProperty(environment, "spring.cloud.nacos.discovery.namespace", namespace);
        requireProperty(environment, "spring.cloud.nacos.config.group", GROUP);
        requireProperty(environment, "spring.cloud.nacos.discovery.group", GROUP);
        requireProperty(environment, "spring.cloud.nacos.config.refresh-enabled", "false");

        String service = environment.getProperty("spring.application.name");
        if (service == null || !service.matches("flowdesk-[a-z0-9-]+")) {
            throw invalid("A valid flowdesk spring.application.name is required");
        }
        requireRemoteMarker(environment, "flowdesk-common.yaml",
                "flowdesk.configuration.environment", profile);
        requireRemoteMarker(environment, service + ".yaml",
                "flowdesk.configuration.service-name", service);
    }

    /**
     * 要求指定配置项与约定值完全一致，不符合时抛出仅包含配置键的异常。
     */
    private static void requireProperty(ConfigurableEnvironment environment, String key, String expected) {
        if (!expected.equals(environment.getProperty(key))) {
            throw invalid("Unexpected deployment setting: " + key);
        }
    }

    /**
     * 在指定 Data ID 的远程属性源中检查标记，防止本地同名属性掩盖远程配置缺失。
     */
    private static void requireRemoteMarker(ConfigurableEnvironment environment, String dataId,
                                            String marker, String expected) {
        String sourceName = GROUP + "@" + dataId;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (containsMarker(source, sourceName, marker, expected)) {
                return;
            }
        }
        throw invalid("Missing required Nacos configuration or invalid marker: " + dataId);
    }

    /**
     * 递归展开复合属性源，同时匹配远程文档名称和标记值，兼容 YAML 多文档来源。
     */
    private static boolean containsMarker(PropertySource<?> source, String sourceName,
                                           String marker, String expected) {
        if (source instanceof CompositePropertySource composite) {
            return composite.getPropertySources().stream()
                    .anyMatch(child -> containsMarker(child, sourceName, marker, expected));
        }
        String name = source.getName();
        return (name.equals(sourceName) || name.startsWith(sourceName + " (document #"))
                && expected.equals(source.getProperty(marker));
    }

    /**
     * 构造带统一前缀的部署配置异常，调用方只传键和文档名，避免泄露凭据。
     */
    private static IllegalStateException invalid(String message) {
        // Only configuration keys and Data IDs are included; never credentials or values.
        return new IllegalStateException("FlowDesk managed configuration: " + message);
    }
}
