package com.flowdesk.common.security;

import java.time.Instant;

/** A newly issued JWT and its authoritative expiry. */
public record IssuedJwt(String token, Instant expiresAt) {
}
