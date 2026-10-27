package com.flowdesk.common.security;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅供 IAM 使用的 JWT 签发器。
 * 采用 RS256（RSA PKCS#1 v1.5 + SHA-256）；验签方也仅接受 RS256，不接受 HMAC 算法。
 */
public final class JwtIssuer {

    private final PrivateKey privateKey;
    private final String issuer;
    private final String humanAudience;
    private final String serviceAudience;
    private final long humanTtlSeconds;
    private final long serviceTtlSeconds;

    /**
     * 保存 RSA 签名私钥、签发方及两类受众，要求人类和服务令牌的有效时长均为正数。
     */
    public JwtIssuer(PrivateKey privateKey,
                     String issuer,
                     String humanAudience,
                     String serviceAudience,
                     long humanTtlSeconds,
                     long serviceTtlSeconds) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.humanAudience = Objects.requireNonNull(humanAudience, "humanAudience");
        this.serviceAudience = Objects.requireNonNull(serviceAudience, "serviceAudience");
        if (humanTtlSeconds <= 0 || serviceTtlSeconds <= 0) {
            throw new IllegalArgumentException("JWT TTL must be positive");
        }
        this.humanTtlSeconds = humanTtlSeconds;
        this.serviceTtlSeconds = serviceTtlSeconds;
    }

    /**
     * 校验身份及角色权限集合，按角色上限裁剪权限并排序去重，再签发带独立标识和到期时间的人类 JWT。
     */
    public IssuedJwt issueHuman(UUID tenantId,
                                UUID userId,
                                Collection<RoleCode> roles,
                                Collection<PermissionCode> permissions) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(permissions, "permissions");
        if (roles.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("roles must not contain null");
        }
        if (permissions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("permissions must not contain null");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(humanTtlSeconds);
        EnumSet<RoleCode> roleSet = roles.isEmpty() ? EnumSet.noneOf(RoleCode.class) : EnumSet.copyOf(roles);
        List<String> roleClaims = roleSet.stream().map(Enum::name).sorted().toList();
        List<String> permissionClaims = permissions.stream()
                .filter(permission -> roleSet.stream().anyMatch(permission::isAllowedFor))
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .audience(humanAudience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("token_use", JwtTokenType.HUMAN.claimValue())
                .claim("roles", roleClaims)
                .claim("permissions", permissionClaims)
                .build();
        return sign(claims, expiresAt);
    }

    /**
     * 签发指定租户的服务 JWT，使用服务专属受众且不写入人类权限；空白服务名直接拒绝。
     */
    public IssuedJwt issueService(UUID tenantId, String serviceId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(serviceId, "serviceId");
        if (serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(serviceTtlSeconds);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(serviceId)
                .audience(serviceAudience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("token_use", JwtTokenType.SERVICE.claimValue())
                .build();
        return sign(claims, expiresAt);
    }

    /**
     * 使用 RSA 私钥和 RS256 对声明签名，返回序列化令牌和到期时间，失败时抛出内部状态异常。
     */
    private IssuedJwt sign(JWTClaimsSet claims, Instant expiresAt) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(privateKey));
            return new IssuedJwt(jwt.serialize(), expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign JWT", e);
        }
    }
}
