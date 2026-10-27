package com.flowdesk.common.security;

import java.time.Instant;

/**
 * 新签发的 JWT 及签发方确定的到期时间。
 */
public record IssuedJwt(String token, Instant expiresAt) {
}
