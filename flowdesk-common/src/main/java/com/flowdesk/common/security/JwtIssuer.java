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
 * IAM-only JWT issuer.
 *
 * <p>Uses RS256 (RSA PKCS#1 v1.5 signature with SHA-256) and only exists in the IAM
 * deployment unit. Verifiers accept RS256 only and never HMAC/HS algorithms.</p>
 */
public final class JwtIssuer {

    private final PrivateKey privateKey;
    private final String issuer;
    private final String humanAudience;
    private final String serviceAudience;
    private final long humanTtlSeconds;
    private final long serviceTtlSeconds;

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
