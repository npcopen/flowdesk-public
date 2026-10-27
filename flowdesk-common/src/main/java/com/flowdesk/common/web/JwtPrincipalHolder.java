package com.flowdesk.common.web;

import com.flowdesk.common.security.JwtPrincipal;

import java.util.Objects;

/**
 * 在当前请求线程中保存已验证的 JWT 主体，并与请求作用域同步清理。
 */
public final class JwtPrincipalHolder {

    private static final ThreadLocal<JwtPrincipal> PRINCIPAL = new ThreadLocal<>();

    /**
     * 禁止直接实例化该工具类型，统一通过静态方法或工厂入口使用。
     */
    private JwtPrincipalHolder() {
    }

    /**
     * 读取当前线程已验证主体，未建立认证作用域时立即失败，禁止使用默认身份。
     */
    public static JwtPrincipal current() {
        return Objects.requireNonNull(PRINCIPAL.get(), "No verified JWT principal in current scope");
    }

    /**
     * 读取当前线程主体，未设置时返回 null，供可选认证检查使用。
     */
    public static JwtPrincipal currentOrNull() {
        return PRINCIPAL.get();
    }

    /**
     * 将已验证主体绑定当前线程，供权限拦截器和业务层读取，退出请求时必须清理。
     */
    public static void set(JwtPrincipal principal) {
        PRINCIPAL.set(Objects.requireNonNull(principal, "principal"));
    }

    /**
     * 移除线程绑定主体，防止线程池复用时身份泄露到后续请求。
     */
    public static void clear() {
        PRINCIPAL.remove();
    }
}
