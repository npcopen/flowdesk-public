package com.flowdesk.gateway.security;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 网关路由与过滤器共用的路径分类规则。
 * 采用与路由表一致的 Spring 路径匹配语义，兼容 Webhook、健康探针的无尾斜杠路径，
 * 防止路由与过滤器分类不一致而误删安全头或错误要求人类 JWT。
 */
public final class GatewayPathClassifier {

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final PathPattern LOGIN = PARSER.parse("/api/v1/auth/login");
    private static final PathPattern WEBHOOKS = PARSER.parse("/api/v1/webhooks/**");
    private static final PathPattern INTERNAL_HEALTH = PARSER.parse("/api/v1/internal/health/**");

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
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

    /**
     * 除登录、Webhook 和健康探针外，所有路径都归为需要人类 JWT 的保护范围，空路径也不豁免。
     * 未分类路径不会匿名放行；最终是否存在可转发的业务端点仍由路由表决定。
     */
    public static boolean requiresHumanJwt(String path) {
        if (path == null) {
            return true;
        }
        return !isLogin(path) && !isWebhook(path) && !isInternalHealth(path);
    }

    /**
     * 用预解析的路径模式匹配请求路径，空路径直接返回 false。
     */
    private static boolean matches(PathPattern pattern, String path) {
        return path != null && pattern.matches(PathContainer.parsePath(path));
    }
}
