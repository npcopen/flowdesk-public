package com.flowdesk.common.security;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextSource;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cryptographically verified claims from a FlowDesk JWT.
 *
 * <p>Instances are created only by {@link JwtVerifier} after signature, algorithm,
 * issuer, audience and time-window checks succeed. They are the sole basis for
 * building a trusted {@link TenantContext}; never construct one from client input.</p>
 */
public final class JwtPrincipal {

    private final JwtTokenType tokenType;
    private final UUID tenantId;
    private final String subject;
    private final Set<RoleCode> roles;
    private final Set<PermissionCode> permissions;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final UUID tokenId;

    private JwtPrincipal(JwtTokenType tokenType,
                         UUID tenantId,
                         String subject,
                         Set<RoleCode> roles,
                         Set<PermissionCode> permissions,
                         Instant issuedAt,
                         Instant expiresAt,
                         UUID tokenId) {
        this.tokenType = Objects.requireNonNull(tokenType, "tokenType");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
    }

    public static JwtPrincipal human(UUID tenantId,
                                     UUID userId,
                                     Set<RoleCode> roles,
                                     Set<PermissionCode> permissions,
                                     Instant issuedAt,
                                     Instant expiresAt,
                                     UUID tokenId) {
        return new JwtPrincipal(JwtTokenType.HUMAN, tenantId, userId.toString(),
                roles, permissions, issuedAt, expiresAt, tokenId);
    }

    public static JwtPrincipal service(UUID tenantId,
                                       String serviceId,
                                       Instant issuedAt,
                                       Instant expiresAt,
                                       UUID tokenId) {
        return new JwtPrincipal(JwtTokenType.SERVICE, tenantId, serviceId,
                Set.of(), Set.of(), issuedAt, expiresAt, tokenId);
    }

    public JwtTokenType tokenType() {
        return tokenType;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID tokenId() {
        return tokenId;
    }

    /** {@code sub} claim: UUID string for humans, deployment-unit id for services. */
    public String subject() {
        return subject;
    }

    public UUID humanUserId() {
        if (tokenType != JwtTokenType.HUMAN) {
            throw new IllegalStateException("Principal is not a human JWT");
        }
        return UUID.fromString(subject);
    }

    public String serviceId() {
        if (tokenType != JwtTokenType.SERVICE) {
            throw new IllegalStateException("Principal is not a service JWT");
        }
        return subject;
    }

    public Set<RoleCode> roles() {
        return roles;
    }

    public Set<PermissionCode> permissions() {
        return permissions;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Builds a trusted context for the verified human JWT.
     *
     * @throws IllegalStateException if this is not a human principal
     */
    public TenantContext toHumanTenantContext(UUID correlationId) {
        if (tokenType != JwtTokenType.HUMAN) {
            throw new IllegalStateException("Service JWT cannot build a HUMAN_JWT TenantContext");
        }
        return TenantContext.ofHumanJwt(tenantId, humanUserId(), correlationId);
    }

    /**
     * Builds a trusted context for the verified service JWT.
     *
     * @throws IllegalStateException if this is not a service principal
     */
    public TenantContext toServiceTenantContext(UUID correlationId) {
        if (tokenType != JwtTokenType.SERVICE) {
            throw new IllegalStateException("Human JWT cannot build a SERVICE_JWT TenantContext");
        }
        return TenantContext.ofServiceJwt(tenantId, serviceId(), correlationId);
    }

    public ActorRef actor() {
        return switch (tokenType) {
            case HUMAN -> ActorRef.human(humanUserId());
            case SERVICE -> ActorRef.service(serviceId());
        };
    }

    public boolean isSource(TenantContextSource source) {
        return switch (source) {
            case HUMAN_JWT -> tokenType == JwtTokenType.HUMAN;
            case SERVICE_JWT -> tokenType == JwtTokenType.SERVICE;
            default -> false;
        };
    }
}
