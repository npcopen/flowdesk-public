package com.flowdesk.common.web;

import com.flowdesk.common.security.JwtPrincipal;

import java.util.Objects;

/** Thread-local handle to the verified JWT principal for the current request scope. */
public final class JwtPrincipalHolder {

    private static final ThreadLocal<JwtPrincipal> PRINCIPAL = new ThreadLocal<>();

    private JwtPrincipalHolder() {
    }

    public static JwtPrincipal current() {
        return Objects.requireNonNull(PRINCIPAL.get(), "No verified JWT principal in current scope");
    }

    public static JwtPrincipal currentOrNull() {
        return PRINCIPAL.get();
    }

    public static void set(JwtPrincipal principal) {
        PRINCIPAL.set(Objects.requireNonNull(principal, "principal"));
    }

    public static void clear() {
        PRINCIPAL.remove();
    }
}
