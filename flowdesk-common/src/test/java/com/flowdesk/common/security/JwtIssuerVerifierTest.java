package com.flowdesk.common.security;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证人类和服务 JWT 的签发验签契约，覆盖主体类型、受保护声明、签名算法及过期分类。
 */
class JwtIssuerVerifierTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static JwtIssuer issuer;
    private static JwtVerifier verifier;
    private static KeyPair keyPair;

    /**
     * 初始化当前测试所需签名密钥、认证配置或 Mock MVC 环境，使后续行为断言基于隔离依赖。
     */
    @BeforeAll
    static void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, 300);
        verifier = new JwtVerifier(keyPair.getPublic(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 30);
    }

    /**
     * 验证人类 JWT 签发与验签往返后保留租户、用户、角色、权限和到期等受保护声明。
     */
    @Test
    void humanTokenRoundTripsProtectedClaims() {
        IssuedJwt issued = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.REQUESTER), Set.of(PermissionCode.TICKET_CREATE));

        JwtPrincipal principal = verifier.verifyHuman(issued.token());
        assertThat(principal.tokenType()).isEqualTo(JwtTokenType.HUMAN);
        assertThat(principal.tenantId()).isEqualTo(TENANT);
        assertThat(principal.humanUserId()).isEqualTo(USER);
        assertThat(principal.roles()).containsExactly(RoleCode.REQUESTER);
        assertThat(principal.permissions()).containsExactly(PermissionCode.TICKET_CREATE);
        assertThat(principal.expiresAt().getEpochSecond()).isEqualTo(issued.expiresAt().getEpochSecond());
    }

    /**
     * 验证人类令牌签发拒绝 null 角色或权限集合，避免不明确授权输入被默认解释。
     */
    @Test
    void humanIssueRejectsNullRoleOrPermissionCollections() {
        assertThatThrownBy(() -> issuer.issueHuman(TENANT, USER, null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueHuman(TENANT, USER, Set.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> issuer.issueHuman(TENANT, USER,
                java.util.Collections.singleton(null), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issuer.issueHuman(TENANT, USER,
                Set.of(), java.util.Collections.singleton(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证服务 JWT 能正确往返验签，同时不能被用于人类身份入口。
     */
    @Test
    void serviceTokenRoundTripsAndCannotBeUsedAsHumanToken() {
        IssuedJwt issued = issuer.issueService(TENANT, "flowdesk-ticket-service");
        JwtPrincipal principal = verifier.verifyService(issued.token());

        assertThat(principal.tokenType()).isEqualTo(JwtTokenType.SERVICE);
        assertThat(principal.serviceId()).isEqualTo("flowdesk-ticket-service");
        assertThatThrownBy(() -> verifier.verifyHuman(issued.token()))
                .isInstanceOf(JwtVerificationException.class)
                .extracting(e -> ((JwtVerificationException) e).kind())
                .isEqualTo(JwtVerificationException.Kind.TYPE_REJECTED);
    }

    /**
     * 验证其他密钥签名的令牌不能通过当前验签器，阻止伪造可信主体。
     */
    @Test
    void wrongKeyIsRejected() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        JwtVerifier otherVerifier = new JwtVerifier(other.getPublic(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 30);
        IssuedJwt issued = issuer.issueHuman(TENANT, USER, Set.of(), Set.of());

        assertThatThrownBy(() -> otherVerifier.verifyHuman(issued.token()))
                .isInstanceOf(JwtVerificationException.class)
                .extracting(e -> ((JwtVerificationException) e).kind())
                .isEqualTo(JwtVerificationException.Kind.SIGNATURE_INVALID);
    }

    /**
     * 验证验签器严格限制签名算法，即使提供相同秘密也拒绝 HS256 令牌。
     */
    @Test
    void hs256TokenIsRejectedEvenWithTheSameSecret() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("flowdesk-iam-service")
                .subject(USER.toString())
                .audience("flowdesk-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", TENANT.toString())
                .claim("token_use", JwtTokenType.HUMAN.claimValue())
                .build();
        SignedJWT hs256 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        hs256.sign(new MACSigner(new byte[32]));

        assertThatThrownBy(() -> verifier.verifyHuman(hs256.serialize()))
                .isInstanceOf(JwtVerificationException.class)
                .extracting(e -> ((JwtVerificationException) e).kind())
                .isEqualTo(JwtVerificationException.Kind.ALGORITHM_REJECTED);
    }

    /**
     * 验证过期令牌得到明确的过期分类，使认证错误与格式或签名错误可区分。
     */
    @Test
    void expiredTokenIsClassifiedAsExpired() {
        JwtVerifier shortLivedVerifier = new JwtVerifier(keyPair.getPublic(),
                "flowdesk-iam-service", "flowdesk-api", "flowdesk-internal", 0);
        // Issue directly with an issuer configured for a negative TTL (not accepted), so mutate
        // a normal token is impossible. Instead build an already expired token with Nimbus.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("flowdesk-iam-service")
                .subject(USER.toString())
                .audience("flowdesk-api")
                .issueTime(Date.from(Instant.now().minusSeconds(600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("tenant_id", TENANT.toString())
                .claim("token_use", JwtTokenType.HUMAN.claimValue())
                .claim("roles", java.util.List.of())
                .claim("permissions", java.util.List.of())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(keyPair.getPrivate()));
            assertThatThrownBy(() -> shortLivedVerifier.verifyHuman(jwt.serialize()))
                    .isInstanceOf(JwtVerificationException.class)
                    .extracting(e -> ((JwtVerificationException) e).kind())
                    .isEqualTo(JwtVerificationException.Kind.EXPIRED);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
