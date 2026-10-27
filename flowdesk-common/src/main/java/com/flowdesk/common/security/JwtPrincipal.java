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
 * 通过密码学验证的 FlowDesk JWT 声明。
 * 仅由 {@link JwtVerifier} 在签名、算法、签发者、受众和有效期校验成功后创建，
 * 作为构建可信 {@link TenantContext} 的依据，不能直接从客户端输入构造。
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

    /**
     * 保存已验证的身份及令牌时间，把角色权限复制为不可变集合，拒绝缺失的必需声明。
     */
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

    /**
     * 从调用方提供的身份声明构造人类主体，保留租户、用户、角色权限和令牌时间。
     * 本工厂不执行验签或权限裁剪；调用方必须先验证声明，后续授权仍由权限策略负责。
     */
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

    /**
     * 从调用方已验证的服务声明构造主体，角色和权限固定为空；本工厂自身不执行 JWT 验签。
     */
    public static JwtPrincipal service(UUID tenantId,
                                       String serviceId,
                                       Instant issuedAt,
                                       Instant expiresAt,
                                       UUID tokenId) {
        return new JwtPrincipal(JwtTokenType.SERVICE, tenantId, serviceId,
                Set.of(), Set.of(), issuedAt, expiresAt, tokenId);
    }

    /**
     * 返回已验证令牌的身份类型，用于区分人类请求与服务间调用。
     */
    public JwtTokenType tokenType() {
        return tokenType;
    }

    /**
     * 返回签名声明确认的租户标识，业务查询仍须将其作为租户隔离条件。
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * 返回 JWT 唯一标识，用于区分不同令牌签发实例。
     */
    public UUID tokenId() {
        return tokenId;
    }

    /** {@code sub} 声明：人类主体使用用户 UUID，服务主体使用部署单元标识。 */
    public String subject() {
        return subject;
    }

    /**
     * 仅在人类身份上解析用户 UUID，服务主体调用时抛出 IllegalStateException。
     */
    public UUID humanUserId() {
        if (tokenType != JwtTokenType.HUMAN) {
            throw new IllegalStateException("Principal is not a human JWT");
        }
        return UUID.fromString(subject);
    }

    /**
     * 仅在服务身份上返回服务名，人类主体调用时抛出 IllegalStateException。
     */
    public String serviceId() {
        if (tokenType != JwtTokenType.SERVICE) {
            throw new IllegalStateException("Principal is not a service JWT");
        }
        return subject;
    }

    /**
     * 返回令牌中的角色集合，实际权限仍需与角色固定上限求交集。
     */
    public Set<RoleCode> roles() {
        return roles;
    }

    /**
     * 返回令牌授予的权限集合，供业务策略结合角色、租户及资源归属继续检查。
     */
    public Set<PermissionCode> permissions() {
        return permissions;
    }

    /**
     * 返回令牌签发时间，供调用方记录身份的时间边界。
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * 返回令牌到期时间，令牌是否过期由验证器统一检查。
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * 为已验证的人类 JWT 构造可信上下文。
     * @throws IllegalStateException 当前主体不是人类主体
     */
    public TenantContext toHumanTenantContext(UUID correlationId) {
        if (tokenType != JwtTokenType.HUMAN) {
            throw new IllegalStateException("Service JWT cannot build a HUMAN_JWT TenantContext");
        }
        return TenantContext.ofHumanJwt(tenantId, humanUserId(), correlationId);
    }

    /**
     * 为已验证的服务 JWT 构造可信上下文。
     * @throws IllegalStateException 当前主体不是服务主体
     */
    public TenantContext toServiceTenantContext(UUID correlationId) {
        if (tokenType != JwtTokenType.SERVICE) {
            throw new IllegalStateException("Human JWT cannot build a SERVICE_JWT TenantContext");
        }
        return TenantContext.ofServiceJwt(tenantId, serviceId(), correlationId);
    }

    /**
     * 按令牌类型转换为人类或服务操作者，保持租户上下文与已验证主体一致。
     */
    public ActorRef actor() {
        return switch (tokenType) {
            case HUMAN -> ActorRef.human(humanUserId());
            case SERVICE -> ActorRef.service(serviceId());
        };
    }

    /**
     * 判断给定上下文来源是否匹配令牌类型；消息、Webhook 等来源不能由 JWT 直接证明。
     */
    public boolean isSource(TenantContextSource source) {
        return switch (source) {
            case HUMAN_JWT -> tokenType == JwtTokenType.HUMAN;
            case SERVICE_JWT -> tokenType == JwtTokenType.SERVICE;
            default -> false;
        };
    }
}
