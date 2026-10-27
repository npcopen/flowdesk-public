package com.flowdesk.common.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证受管环境的 Nacos 配置启动约束，覆盖文档来源、环境和服务身份标记及禁止漂移的配置项。
 */
class ManagedNacosConfigurationGuardTest {

    private final ManagedNacosConfigurationGuard guard = new ManagedNacosConfigurationGuard();

    /**
     * 验证 dev、test、prod 中完整且环境标识匹配的远程公共与服务配置可以通过启动检查。
     */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "prod"})
    void acceptsCompleteMatchingRemoteConfiguration(String profile) {
        assertThatCode(() -> guard.postProcessEnvironment(valid(profile), null)).doesNotThrowAnyException();
    }

    /**
     * 验证未受管的本地和单元测试启动不强制连接 Nacos，保留独立运行能力。
     */
    @Test
    void leavesUnmanagedLocalAndUnitTestStartsAlone() {
        MockEnvironment environment = new MockEnvironment();
        assertThatCode(() -> guard.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
        environment.setActiveProfiles("unit-test");
        assertThatCode(() -> guard.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    /**
     * 验证同时激活多个受管环境时启动失败，避免混用不同命名空间的配置。
     */
    @Test
    void rejectsMultipleManagedEnvironments() {
        MockEnvironment environment = valid("dev");
        environment.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("Exactly one");
    }

    /**
     * 验证受管环境必须通过 active profiles 明确选择，不能仅依靠默认 profile 隐式进入。
     */
    @Test
    void requiresExplicitEnvironmentSelection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("dev");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("spring.profiles.active");
    }

    /**
     * 验证配置与注册中心命名空间及分组偏离当前环境时立即拒绝启动。
     */
    @ParameterizedTest
    @ValueSource(strings = {"spring.cloud.nacos.config.namespace", "spring.cloud.nacos.discovery.namespace",
            "spring.cloud.nacos.config.group", "spring.cloud.nacos.discovery.group"})
    void rejectsEnvironmentOrGroupDrift(String key) {
        MockEnvironment environment = valid("dev").withProperty(key, "wrong-environment");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null)).hasMessageContaining(key);
    }

    /**
     * 验证受管环境不能关闭远程配置或启用动态刷新，避免运行时偏离已验证配置边界。
     */
    @Test
    void rejectsDisablingConfigOrEnablingRefreshInAManagedEnvironment() {
        MockEnvironment disabled = valid("dev").withProperty("spring.cloud.nacos.config.enabled", "false");
        assertThatThrownBy(() -> guard.postProcessEnvironment(disabled, null))
                .hasMessageContaining("config.enabled");
        MockEnvironment refresh = valid("dev").withProperty("spring.cloud.nacos.config.refresh-enabled", "true");
        assertThatThrownBy(() -> guard.postProcessEnvironment(refresh, null))
                .hasMessageContaining("config.refresh-enabled");
    }

    /**
     * 验证本地伪造服务配置标记不能替代缺失的 Nacos 服务文档。
     */
    @Test
    void rejectsMissingServiceEvenWhenLocalPropertiesSupplyTheMarker() {
        MockEnvironment environment = valid("dev");
        environment.getPropertySources().remove("FLOWDESK_GROUP@flowdesk-gateway.yaml");
        environment.setProperty("flowdesk.configuration.service-name", "flowdesk-gateway");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("Missing required Nacos configuration")
                .hasMessageContaining("flowdesk-gateway.yaml");
    }

    /**
     * 验证本地环境标记不能替代必须加载的 Nacos 公共配置文档。
     */
    @Test
    void rejectsMissingCommonEvenWhenLocalPropertiesSupplyTheMarker() {
        MockEnvironment environment = valid("dev");
        environment.getPropertySources().remove("FLOWDESK_GROUP@flowdesk-common.yaml");
        environment.setProperty("flowdesk.configuration.environment", "dev");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("flowdesk-common.yaml");
    }

    /**
     * 验证远程公共配置声明的环境与当前命名空间不符时拒绝启动。
     */
    @Test
    void rejectsConfigPublishedIntoTheWrongNamespace() {
        MockEnvironment environment = valid("dev");
        environment.getPropertySources().replace("FLOWDESK_GROUP@flowdesk-common.yaml",
                remote("flowdesk-common.yaml", "flowdesk.configuration.environment", "prod"));
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("invalid marker");
    }

    /**
     * 验证服务配置中的身份标记必须属于当前服务，不能误载其他服务配置。
     */
    @Test
    void rejectsServiceDocumentWithTheWrongIdentity() {
        MockEnvironment environment = valid("dev");
        environment.getPropertySources().replace("FLOWDESK_GROUP@flowdesk-gateway.yaml",
                remote("flowdesk-gateway.yaml", "flowdesk.configuration.service-name", "flowdesk-iam-service"));
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, null))
                .hasMessageContaining("flowdesk-gateway.yaml");
    }

    /**
     * 验证组合 PropertySource 中的 YAML 分文档仍可被识别为有效 Nacos 来源。
     */
    @Test
    void acceptsNacosYamlDocumentSourcesInsideAComposite() {
        MockEnvironment environment = valid("dev");
        environment.getPropertySources().remove("FLOWDESK_GROUP@flowdesk-common.yaml");
        CompositePropertySource composite = new CompositePropertySource("NACOS");
        composite.addPropertySource(new MapPropertySource("FLOWDESK_GROUP@flowdesk-common.yaml (document #0)",
                Map.of("flowdesk.configuration.environment", "dev")));
        environment.getPropertySources().addFirst(composite);
        assertThatCode(() -> guard.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    /**
     * 构造具备完整远程公共和服务标记的受管环境，供各项失败测试只修改一个约束。
     */
    private static MockEnvironment valid(String profile) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "flowdesk-gateway")
                .withProperty("spring.cloud.nacos.config.enabled", "true")
                .withProperty("spring.cloud.nacos.config.namespace", "flowdesk-" + profile)
                .withProperty("spring.cloud.nacos.discovery.namespace", "flowdesk-" + profile)
                .withProperty("spring.cloud.nacos.config.group", "FLOWDESK_GROUP")
                .withProperty("spring.cloud.nacos.discovery.group", "FLOWDESK_GROUP")
                .withProperty("spring.cloud.nacos.config.refresh-enabled", "false");
        environment.setActiveProfiles(profile);
        environment.getPropertySources().addFirst(remote("flowdesk-common.yaml",
                "flowdesk.configuration.environment", profile));
        environment.getPropertySources().addFirst(remote("flowdesk-gateway.yaml",
                "flowdesk.configuration.service-name", "flowdesk-gateway"));
        return environment;
    }

    /**
     * 创建模拟 Nacos 文档的属性来源，保留 Data ID 和分组命名以覆盖来源识别逻辑。
     */
    private static MapPropertySource remote(String dataId, String key, String value) {
        return new MapPropertySource("FLOWDESK_GROUP@" + dataId, Map.of(key, value));
    }
}
