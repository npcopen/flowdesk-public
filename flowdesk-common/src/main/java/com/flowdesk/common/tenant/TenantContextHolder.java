package com.flowdesk.common.tenant;

import java.util.Objects;

/**
 * 当前线程作用域内可信 TenantContext 的持有器。
 *
 * <p>任何代码路径都不允许在没有可信上下文时猜一个默认租户：{@link #current()}
 * 在缺失时抛出 {@link NoTenantContextException}。上下文必须通过
 * {@link TenantContextScope}（try-with-resources 保证 finally 清理）建立，
 * 不能依赖线程继承或进程全局变量在请求间泄漏。</p>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * 返回当前可信上下文。
     *
     * @return 当前上下文
     * @throws NoTenantContextException 当前作用域内没有可信上下文
     */
    public static TenantContext current() {
        TenantContext context = CONTEXT.get();
        if (context == null) {
            throw new NoTenantContextException();
        }
        return context;
    }

    /**
     * 返回当前可信上下文，缺失时返回 {@code null}（仅用于边界判定，不替代授权）。
     *
     * @return 当前上下文或 {@code null}
     */
    public static TenantContext currentOrNull() {
        return CONTEXT.get();
    }

    /**
     * 在当前线程设置可信上下文。只应由 {@link TenantContextScope} 或受信入口调用。
     *
     * @param context 非空可信上下文
     */
    public static void set(TenantContext context) {
        CONTEXT.set(Objects.requireNonNull(context, "context"));
    }

    /** 清除当前线程的上下文。任何作用域结束（含异常路径）都必须清理。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
