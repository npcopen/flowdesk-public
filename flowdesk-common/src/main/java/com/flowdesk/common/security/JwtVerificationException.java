package com.flowdesk.common.security;

/**
 * A JWT failed one of the required cryptographic/protocol checks.
 *
 * <p>{@link Kind} is for internal/observability classification only. The public
 * API maps every category to a fixed stable error code and never exposes the reason.</p>
 */
public class JwtVerificationException extends RuntimeException {

    public enum Kind {
        MALFORMED,
        ALGORITHM_REJECTED,
        SIGNATURE_INVALID,
        ISSUER_REJECTED,
        AUDIENCE_REJECTED,
        EXPIRED,
        TYPE_REJECTED,
        CLAIMS_INVALID
    }

    private final Kind kind;

    public JwtVerificationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public JwtVerificationException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
