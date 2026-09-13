package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared signing material for opaque page-cursor tokens. The key must be identical on
 * every IAM instance and stable across restarts, otherwise cursors issued by one
 * instance (or before a restart) cannot be decoded by another.
 */
@ConfigurationProperties(prefix = "flowdesk.security.cursor-token")
public class CursorTokenProperties {

    /** Base64-encoded HMAC-SHA-256 key of at least 32 random bytes. */
    private String hmacKey;

    public String hmacKey() {
        return hmacKey;
    }

    public void setHmacKey(String hmacKey) {
        this.hmacKey = hmacKey;
    }
}
