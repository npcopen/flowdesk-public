package com.flowdesk.iam.security;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.iam.repository.IamRepository;
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

class ServiceJwtBootstrapRunnerTest {

    private static final UUID TENANT = UUID.fromString("12d7dca8-380c-5a6c-84ae-953502959486");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @TempDir
    Path tempDir;

    @Test
    void writesTokensOnlyForExistingAllowedTenants() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(TENANT + ":flowdesk-ticket-service"));
        IamRepository repository = mock(IamRepository.class);
        when(repository.tenantExists(TENANT)).thenReturn(true);
        ServiceJwtBootstrapRunner runner = new ServiceJwtBootstrapRunner(
                properties, issuanceService(), repository);
        runner.setApplicationContext(new StaticApplicationContext());

        runner.run(new DefaultApplicationArguments(new String[0]));

        Path tokenFile = tempDir.resolve(TENANT + "-flowdesk-ticket-service.jwt");
        assertThat(Files.readString(tokenFile)).isNotBlank();
    }

    @Test
    void rejectsDuplicateMappingsBeforeWritingAnyTokenFile() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(List.of(
                TENANT + ":flowdesk-ticket-service",
                TENANT + ":flowdesk-ticket-service"));
        IamRepository repository = mock(IamRepository.class);
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

    @Test
    void rejectsUnknownTenantBeforeWritingAnyTokenFile() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(UNKNOWN_TENANT + ":flowdesk-ticket-service"));
        IamRepository repository = mock(IamRepository.class);
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

    @Test
    void continuouslyRotatesTokenFilesWithoutPartialArtifacts() throws Exception {
        ServiceJwtBootstrapProperties properties = bootstrapProperties(
                List.of(TENANT + ":flowdesk-ticket-service"));
        properties.setExitAfterIssue(false);
        properties.setRefreshInterval(Duration.ofMillis(100));
        IamRepository repository = mock(IamRepository.class);
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

    private ServiceJwtBootstrapProperties bootstrapProperties(List<String> mappings) {
        ServiceJwtBootstrapProperties properties = new ServiceJwtBootstrapProperties();
        properties.setEnabled(true);
        properties.setOutputDirectory(tempDir.toString());
        properties.setMappings(mappings);
        return properties;
    }

    private static ServiceJwtIssuanceService issuanceService() throws Exception {
        return issuanceService(300);
    }

    private static ServiceJwtIssuanceService issuanceService(long serviceTtlSeconds) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        FlowDeskJwtProperties jwtProperties = new FlowDeskJwtProperties();
        jwtProperties.setAllowedServiceIds(List.of("flowdesk-ticket-service", "flowdesk-workflow-service"));
        JwtIssuer issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, serviceTtlSeconds);
        return new ServiceJwtIssuanceService(issuer, jwtProperties);
    }
}
