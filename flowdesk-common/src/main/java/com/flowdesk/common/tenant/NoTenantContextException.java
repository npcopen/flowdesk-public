package com.flowdesk.common.tenant;

/**
 * 当前作用域内不存在可信 TenantContext 时抛出。
 *
 * <p>禁止默认租户：任何代码路径都不能在缺少可信上下文时猜一个租户继续。
 * 缺失上下文进入可观察异常，而不是静默使用默认值。</p>
 */
public class NoTenantContextException extends IllegalStateException {

    /**
     * 标记当前作用域缺少受信任租户上下文，让调用方停止资源访问而非使用默认租户。
     */
    public NoTenantContextException() {
        super("No trusted TenantContext is present in the current scope");
    }
}
