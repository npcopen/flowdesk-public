package com.flowdesk.gateway.security;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Single source of truth for gateway route classification (ARCHITECTURE §7.4).
 *
 * <p>The route table matches {@code /api/v1/webhooks/**} and
 * {@code /api/v1/internal/health/**} with Spring path patterns, which also match the
 * bare paths ({@code /api/v1/webhooks}, {@code /api/v1/internal/health}). Filters that
 * classify requests must use the same matching semantics, otherwise a bare-path webhook
 * request would be treated as a protected human API and stripped of its security headers.
 * All gateway components derive their classification from here so predicate and filters
 * can never drift apart.</p>
 */
public final class GatewayPathClassifier {

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final PathPattern LOGIN = PARSER.parse("/api/v1/auth/login");
    private static final PathPattern WEBHOOKS = PARSER.parse("/api/v1/webhooks/**");
    private static final PathPattern INTERNAL_HEALTH = PARSER.parse("/api/v1/internal/health/**");
    private static final PathPattern SPA_ASSETS = PARSER.parse("/assets/**");

    private GatewayPathClassifier() {
    }

    /** 登录路径：匿名公开，不要求 JWT。 */
    public static boolean isLogin(String path) {
        return matches(LOGIN, path);
    }

    /** Webhook 路径：不要求人类 JWT，且保留 Webhook 安全头。 */
    public static boolean isWebhook(String path) {
        return matches(WEBHOOKS, path);
    }

    /** 服务本地健康探针路径：不要求人类 JWT。 */
    public static boolean isInternalHealth(String path) {
        return matches(INTERNAL_HEALTH, path);
    }

    /** Public static files emitted by the Vue build. */
    public static boolean isSpaAsset(String path) {
        return matches(SPA_ASSETS, path);
    }

    /** Browser history routes handled by the SPA shell. */
    public static boolean isSpaRoute(String path) {
        if (path == null || path.startsWith("/api/")) {
            return false;
        }
        if ("/".equals(path) || "/login".equals(path)
                || "/tickets".equals(path) || "/approvals".equals(path)
                || "/alerts".equals(path) || "/notifications".equals(path)
                || "/settings/users".equals(path) || "/settings/catalog".equals(path)) {
            return true;
        }
        return matches(PARSER.parse("/tickets/**"), path)
                || matches(PARSER.parse("/approvals/**"), path)
                || matches(PARSER.parse("/alerts/**"), path);
    }

    /**
     * 人类受保护 API：deny-by-default。除登录、Webhook、健康探针外，所有路径
     * （含未来的非 /api/v1 路由）都要求人类 JWT；未分类路径默认拒绝。
     */
    public static boolean requiresHumanJwt(String path) {
        if (path == null) {
            return true;
        }
        return !isLogin(path) && !isWebhook(path) && !isInternalHealth(path)
                && !isSpaAsset(path) && !isSpaRoute(path);
    }

    private static boolean matches(PathPattern pattern, String path) {
        return path != null && pattern.matches(PathContainer.parsePath(path));
    }
}
