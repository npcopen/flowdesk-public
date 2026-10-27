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

/**
 * 验证服务 JWT 白名单签发的行为约束；结合可控测试数据检查正常流程、失败边界或状态收敛，避免回归破坏既有契约。
 */
class ServiceJwtIssuanceServiceTest {

    private static final UUID TENANT = UUID.fromString("12d7dca8-380c-5a6c-84ae-953502959486");

    private static ServiceJwtIssuanceService issuanceService;
    private static JwtVerifier verifier;

    /**
     * 生成测试签名密钥并组装服务 JWT 签发与验签组件，使后续断言基于真实令牌。
     */
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

    /**
     * 验证仅白名单服务可以获得服务 JWT，且签名主体、租户与到期时间可被验签器正确识别。
     */
    @Test
    void issuesServiceJwtOnlyForConfiguredAllowList() {
        IssuedJwt issued = issuanceService.issue(TENANT, "flowdesk-ticket-service");

        JwtPrincipal principal = verifier.verifyService(issued.token());
        assertThat(principal.tenantId()).isEqualTo(TENANT);
        assertThat(principal.serviceId()).isEqualTo("flowdesk-ticket-service");
        assertThat(principal.expiresAt().getEpochSecond()).isEqualTo(issued.expiresAt().getEpochSecond());
    }

    /**
     * 验证未知服务、空白服务标识及空租户输入被拒绝，不能签发无明确边界的服务身份。
     */
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
