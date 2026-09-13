package com.flowdesk.common.security;

/**
 * JWT token usage category kept in the protected {@code token_use} claim.
 *
 * <p>The claim is set and verified by the issuer/verifiers; a client cannot
 * change it without invalidating the signature.</p>
 */
public enum JwtTokenType {

    /** IAM-issued token for an authenticated human user. */
    HUMAN("human"),

    /** IAM-issued token for an authenticated internal service identity. */
    SERVICE("service");

    private final String claimValue;

    JwtTokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }

    public static JwtTokenType fromClaimValue(String value) {
        for (JwtTokenType type : values()) {
            if (type.claimValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown JWT token_use: " + value);
    }
}
