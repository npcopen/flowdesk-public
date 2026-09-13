package com.flowdesk.iam.security;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.security.JwtPrincipal;
import com.flowdesk.common.security.JwtVerifier;
import com.flowdesk.iam.service.ServiceJwtIssuanceService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceJwtIssuanceServiceTest {

    private static final UUID TENANT = UUID.fromString("12d7dca8-380c-5a6c-84ae-953502959486");

    private static ServiceJwtIssuanceService issuanceService;
    private static JwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        FlowDeskJwtProperties properties = new FlowDeskJwtProperties();
        properties.setAllowedServiceIds(List.of("flowdesk-ticket-service", "flowdesk-workflow-service"));
        JwtIssuer issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, 300);
        issuanceService = new ServiceJwtIssuanceService(issuer, properties);
        verifier = new JwtVerifier(keyPair.getPublic(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 30);
    }

    @Test
    void issuesServiceJwtOnlyForConfiguredAllowList() {
        IssuedJwt issued = issuanceService.issue(TENANT, "flowdesk-ticket-service");

        JwtPrincipal principal = verifier.verifyService(issued.token());
        assertThat(principal.tenantId()).isEqualTo(TENANT);
        assertThat(principal.serviceId()).isEqualTo("flowdesk-ticket-service");
        assertThat(principal.expiresAt().getEpochSecond()).isEqualTo(issued.expiresAt().getEpochSecond());
    }

    @Test
    void rejectsUnknownServiceIdAndBlankInput() {
        assertThatThrownBy(() -> issuanceService.issue(TENANT, "flowdesk-alert-service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-service-ids");

        assertThatThrownBy(() -> issuanceService.issue(TENANT, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuanceService.issue(null, "flowdesk-ticket-service"))
                .isInstanceOf(NullPointerException.class);
    }
}
