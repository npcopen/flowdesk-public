package com.flowdesk.iam.security;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.iam.mapper.CredentialMapper;
import com.flowdesk.iam.service.ServiceJwtIssuanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.StaticApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证服务 JWT 文件签发与轮换的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
class ServiceJwtBootstrapRunnerTest {

    private static final UUID TENANT = UUID.fromString("12d7dca8-380c-5a6c-84ae-953502959486");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @TempDir
    Path tempDir;

    /**
     * 验证服务 JWT 文件仅为已存在且在配置允许范围中的租户服务映射生成。
     */
    @Test
    void writesTokensOnlyForExistingAllowedTenants() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(TENANT + ":flowdesk-ticket-service"));
        CredentialMapper repository = mock(CredentialMapper.class);
        when(repository.tenantExists(TENANT)).thenReturn(true);
        ServiceJwtBootstrapRunner runner = new ServiceJwtBootstrapRunner(
                properties, issuanceService(), repository);
        runner.setApplicationContext(new StaticApplicationContext());

        runner.run(new DefaultApplicationArguments(new String[0]));

        Path tokenFile = tempDir.resolve(TENANT + "-flowdesk-ticket-service.jwt");
        assertThat(Files.readString(tokenFile)).isNotBlank();
    }

    /**
     * 验证重复签发映射在写入任何令牌文件前被拒绝，避免部分输出。
     */
    @Test
    void rejectsDuplicateMappingsBeforeWritingAnyTokenFile() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(List.of(
                TENANT + ":flowdesk-ticket-service",
                TENANT + ":flowdesk-ticket-service"));
        CredentialMapper repository = mock(CredentialMapper.class);
        when(repository.tenantExists(TENANT)).thenReturn(true);
        ServiceJwtBootstrapRunner runner = new ServiceJwtBootstrapRunner(
                properties, issuanceService(), repository);
        runner.setApplicationContext(new StaticApplicationContext());

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate service-jwt-bootstrap mapping");
        try (var files = Files.list(tempDir)) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    /**
     * 验证未知租户映射使整批签发预检失败，不创建任何令牌文件。
     */
    @Test
    void rejectsUnknownTenantBeforeWritingAnyTokenFile() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(UNKNOWN_TENANT + ":flowdesk-ticket-service"));
        CredentialMapper repository = mock(CredentialMapper.class);
        when(repository.tenantExists(UNKNOWN_TENANT)).thenReturn(false);
        ServiceJwtBootstrapRunner runner = new ServiceJwtBootstrapRunner(
                properties, issuanceService(), repository);
        runner.setApplicationContext(new StaticApplicationContext());

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant does not exist");
        try (var files = Files.list(tempDir)) {
            assertThat(files.findAny()).isEmpty();
        }
    }

    /**
     * 验证常驻模式持续轮换令牌文件，并检查替换过程不遗留不完整临时产物。
     */
    @Test
    void continuouslyRotatesTokenFilesWithoutPartialArtifacts() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(TENANT + ":flowdesk-ticket-service"));
        properties.setExitAfterIssue(false);
        properties.setRefreshInterval(Duration.ofMillis(100));
        CredentialMapper repository = mock(CredentialMapper.class);
        when(repository.tenantExists(TENANT)).thenReturn(true);
        ServiceJwtBootstrapRunner runner = new ServiceJwtBootstrapRunner(
                properties, issuanceService(2), repository);
        runner.setApplicationContext(new StaticApplicationContext());

        try {
            runner.run(new DefaultApplicationArguments(new String[0]));
            Path tokenFile = tempDir.resolve(TENANT + "-flowdesk-ticket-service.jwt");
            String first = Files.readString(tokenFile);
            String rotated = first;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (first.equals(rotated) && System.nanoTime() < deadline) {
                Thread.sleep(25);
                rotated = Files.readString(tokenFile);
            }

            assertThat(rotated).isNotEqualTo(first);
            try (var files = Files.list(tempDir)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .allMatch(name -> !name.endsWith(".tmp"));
            }
        } finally {
            runner.destroy();
        }
    }

    /**
     * 构造可控的 JWT 文件签发配置，设置输出目录和租户服务映射以避免依赖开发环境。
     */
    private ServiceJwtBootstrapProperties bootstrapProperties(List<String> mappings) {
        ServiceJwtBootstrapProperties properties = new ServiceJwtBootstrapProperties();
        properties.setEnabled(true);
        properties.setOutputDirectory(tempDir.toString());
        properties.setMappings(mappings);
        return properties;
    }

    /**
     * 创建测试服务 JWT 签发能力，使用测试密钥、允许服务及可控有效期验证文件签发或轮换。
     */
    private static ServiceJwtIssuanceService issuanceService() throws Exception {
        return issuanceService(300);
    }

    /**
     * 创建测试服务 JWT 签发能力，使用测试密钥、允许服务及可控有效期验证文件签发或轮换。
     */
    private static ServiceJwtIssuanceService issuanceService(long serviceTtlSeconds) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        FlowDeskJwtProperties jwtProperties = new FlowDeskJwtProperties();
        jwtProperties.setAllowedServiceIds(List.of("flowdesk-ticket-service", "flowdesk-workflow-service"));
        JwtIssuer issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, serviceTtlSeconds);
        return new ServiceJwtIssuanceService(issuer, jwtProperties);
    }
}
