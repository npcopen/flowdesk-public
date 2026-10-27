package com.flowdesk.common.security;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Gateway 和各业务服务共用的 JWT 验证器。
 * 每一跳都会重新验证签名、签发者、受众和有效期，不能仅因请求经过 Gateway 就信任其身份。
 */
public final class JwtVerifier {

    private final PublicKey publicKey;
    private final String issuer;
    private final String humanAudience;
    private final String serviceAudience;
    private final long clockSkewSeconds;

    /**
     * 要求 RSA 公钥并保存签发方、两类受众及时钟偏差，作为后续令牌验证的统一配置。
     */
    public JwtVerifier(PublicKey publicKey,
                       String issuer,
                       String humanAudience,
                       String serviceAudience,
                       long clockSkewSeconds) {
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new IllegalArgumentException("JWT verification requires an RSA public key");
        }
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.humanAudience = Objects.requireNonNull(humanAudience, "humanAudience");
        this.serviceAudience = Objects.requireNonNull(serviceAudience, "serviceAudience");
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * 按人类身份类型及人类专用受众验证令牌，成功后返回可建立租户上下文的主体。
     */
    public JwtPrincipal verifyHuman(String token) {
        return verify(token, JwtTokenType.HUMAN, humanAudience);
    }

    /**
     * 按服务身份类型及服务专用受众验证令牌，避免人类 JWT 被用于内部接口。
     */
    public JwtPrincipal verifyService(String token) {
        return verify(token, JwtTokenType.SERVICE, serviceAudience);
    }

    /**
     * 校验 RS256 和可选 typ 头并验证签名，再检查签发方、带容差的 exp、必需 iat、身份声明及唯一受众。
     * iat 在此仅要求存在，不额外校验其是否晚于当前时间；任何校验失败都按原因分类抛出异常。
     *
     * @param token 待验证的序列化 JWT，不能是空值或空白文本
     * @param expectedType 当前入口允许的身份类型
     * @param expectedAudience 当前入口要求唯一且精确匹配的受众
     * @return 经签名和声明检查后构造的租户主体
     */
    private JwtPrincipal verify(String token, JwtTokenType expectedType, String expectedAudience) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException(JwtVerificationException.Kind.MALFORMED, "JWT is blank");
        }
        final SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (Exception e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.MALFORMED, "JWT is malformed", e);
        }

        if (jwt.getHeader().getAlgorithm() != JWSAlgorithm.RS256) {
            throw new JwtVerificationException(
                    JwtVerificationException.Kind.ALGORITHM_REJECTED, "Only RS256 is accepted");
        }
        JOSEObjectType type = jwt.getHeader().getType();
        if (type != null && !JOSEObjectType.JWT.equals(type)) {
            throw new JwtVerificationException(
                    JwtVerificationException.Kind.ALGORITHM_REJECTED, "JWT typ header must be JWT or absent");
        }

        // 先校验签名，再读取并信任声明，避免未验证的身份数据进入租户上下文。
        final boolean signatureValid;
        try {
            signatureValid = jwt.verify(new RSASSAVerifier((RSAPublicKey) publicKey));
        } catch (Exception e) {
            throw new JwtVerificationException(
                    JwtVerificationException.Kind.SIGNATURE_INVALID, "JWT signature could not be verified", e);
        }
        if (!signatureValid) {
            throw new JwtVerificationException(
                    JwtVerificationException.Kind.SIGNATURE_INVALID, "JWT signature is invalid");
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (Exception e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.MALFORMED, "JWT claims are malformed", e);
        }

        if (!issuer.equals(claims.getIssuer())) {
            throw new JwtVerificationException(JwtVerificationException.Kind.ISSUER_REJECTED, "Unexpected issuer");
        }
        Date expirationTime = claims.getExpirationTime();
        if (expirationTime == null) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID, "JWT has no exp");
        }
        Instant now = Instant.now();
        if (expirationTime.toInstant().isBefore(now.minusSeconds(clockSkewSeconds))) {
            throw new JwtVerificationException(JwtVerificationException.Kind.EXPIRED, "JWT is expired");
        }
        Date issueTime = claims.getIssueTime();
        if (issueTime == null) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID, "JWT has no iat");
        }

        String tokenUse;
        UUID tenantId;
        String subject = claims.getSubject();
        UUID tokenId;
        try {
            tokenUse = claims.getStringClaim("token_use");
            tenantId = UUID.fromString(claims.getStringClaim("tenant_id"));
            tokenId = UUID.fromString(claims.getJWTID());
        } catch (Exception e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID, "Invalid JWT identity claims", e);
        }
        if (subject == null || subject.isBlank()) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID, "JWT subject is missing");
        }

        final JwtTokenType actualType;
        try {
            actualType = JwtTokenType.fromClaimValue(tokenUse);
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.TYPE_REJECTED, "Unknown token_use", e);
        }
        // 人类令牌与服务令牌用途隔离，不能互相替代。
        if (actualType != expectedType) {
            throw new JwtVerificationException(JwtVerificationException.Kind.TYPE_REJECTED,
                    "Expected " + expectedType + " token but got " + actualType);
        }

        // 只接受唯一且精确匹配的受众，阻止令牌被用于其他服务。
        List<String> audiences = claims.getAudience();
        if (audiences == null || audiences.size() != 1 || !expectedAudience.equals(audiences.get(0))) {
            throw new JwtVerificationException(JwtVerificationException.Kind.AUDIENCE_REJECTED, "Unexpected audience");
        }

        return switch (actualType) {
            case HUMAN -> {
                UUID userId = parseUuid(subject, "sub");
                yield JwtPrincipal.human(tenantId, userId, readRoles(claims), readPermissions(claims),
                        issueTime.toInstant(), expirationTime.toInstant(), tokenId);
            }
            case SERVICE -> JwtPrincipal.service(tenantId, subject, issueTime.toInstant(),
                    expirationTime.toInstant(), tokenId);
        };
    }

    /**
     * 将角色数组转换为去重的枚举集合；缺失则为空，类型错误或未知角色使令牌验证失败。
     */
    private static Set<RoleCode> readRoles(JWTClaimsSet claims) {
        List<String> values;
        try {
            values = claims.getStringListClaim("roles");
        } catch (java.text.ParseException e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                    "Invalid roles claim", e);
        }
        if (values == null) {
            return Set.of();
        }
        EnumSet<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        for (String value : values) {
            try {
                roles.add(RoleCode.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                        "Unknown role in JWT");
            }
        }
        return Set.copyOf(roles);
    }

    /**
     * 读取并去重权限声明，缺失则为空，错误类型或不支持的权限视为非法声明。
     */
    private static Set<PermissionCode> readPermissions(JWTClaimsSet claims) {
        List<String> values;
        try {
            values = claims.getStringListClaim("permissions");
        } catch (java.text.ParseException e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                    "Invalid permissions claim", e);
        }
        if (values == null) {
            return Set.of();
        }
        EnumSet<PermissionCode> permissions = EnumSet.noneOf(PermissionCode.class);
        for (String value : values) {
            try {
                permissions.add(PermissionCode.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                        "Unknown permission in JWT");
            }
        }
        return Set.copyOf(permissions);
    }

    /**
     * 解析指定声明的 UUID，格式错误时统一转换为 CLAIMS_INVALID，保留稳定的认证失败语义。
     */
    private static UUID parseUuid(String value, String claim) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                    "JWT " + claim + " is not a UUID", e);
        }
    }
}
