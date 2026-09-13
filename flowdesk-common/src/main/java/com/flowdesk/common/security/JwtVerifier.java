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
 * Shared JWT verifier used by Gateway (edge first check) and every business service
 * (local second check). Signature/issuer/audience/expiry checks are performed again
 * on each hop; being downstream of Gateway is never a trust root.
 */
public final class JwtVerifier {

    private final PublicKey publicKey;
    private final String issuer;
    private final String humanAudience;
    private final String serviceAudience;
    private final long clockSkewSeconds;

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

    public JwtPrincipal verifyHuman(String token) {
        return verify(token, JwtTokenType.HUMAN, humanAudience);
    }

    public JwtPrincipal verifyService(String token) {
        return verify(token, JwtTokenType.SERVICE, serviceAudience);
    }

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
        if (actualType != expectedType) {
            throw new JwtVerificationException(JwtVerificationException.Kind.TYPE_REJECTED,
                    "Expected " + expectedType + " token but got " + actualType);
        }

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

    private static UUID parseUuid(String value, String claim) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException(JwtVerificationException.Kind.CLAIMS_INVALID,
                    "JWT " + claim + " is not a UUID", e);
        }
    }
}
