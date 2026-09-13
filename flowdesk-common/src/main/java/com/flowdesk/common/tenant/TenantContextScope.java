package com.flowdesk.common.tenant;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 一次请求、消息处理或持久化任务项的可信 TenantContext 作用域。
 *
 * <p>用 try-with-resources 保证无论成功还是异常都在离开作用域时清理线程上下文：
 *
 * <pre>{@code
 * try (TenantContextScope scope = TenantContextScope.open(context)) {
 *     useCase.execute(); // 领域用例进入前上下文已建立
 * }
 * }</pre>
 *
 * <p>支持嵌套：内层作用域关闭后恢复外层上下文。异步线程必须通过
 * {@link #wrap} 显式传播不可变上下文快照，不依赖线程继承（线程池复用不得串租户）。</p>
 */
public final class TenantContextScope implements AutoCloseable {

    private final TenantContext previous;
    private final TenantContext context;

    private TenantContextScope(TenantContext context) {
        this.previous = TenantContextHolder.currentOrNull();
        this.context = Objects.requireNonNull(context, "context");
        TenantContextHolder.set(context);
    }

    /**
     * 在当前线程建立上下文作用域。进入领域用例前调用；离开作用域时无论成功或异常都清理。
     *
     * @param context 非空可信上下文
     * @return 作用域；用 try-with-resources 关闭
     */
    public static TenantContextScope open(TenantContext context) {
        return new TenantContextScope(context);
    }

    /** 本作用域建立的上下文。 */
    public TenantContext context() {
        return context;
    }

    /**
     * 关闭作用域并恢复之前的上下文（无则清空）。
     * 任何路径（含异常）都会调用；幂等。
     */
    @Override
    public void close() {
        if (TenantContextHolder.currentOrNull() == context) {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previous);
            }
        }
    }

    /**
     * 以显式不可变上下文快照包装任务，用于异步线程传播（不依赖线程继承）。
     *
     * @param context 调用方已验证作用域的不可变上下文快照
     * @param task    要执行的任务
     * @return 包装后的任务；执行前建立上下文、结束（含异常）清理
     */
    public static Runnable wrap(TenantContext context, Runnable task) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");
        return () -> {
            try (TenantContextScope ignored = open(context)) {
                task.run();
            }
        };
    }

    /**
     * 以显式不可变上下文快照包装可返回结果的任务，用于异步线程传播（不依赖线程继承）。
     *
     * @param context 调用方已验证作用域的不可变上下文快照
     * @param task    要执行的任务
     * @param <T>     结果类型
     * @return 包装后的任务；执行前建立上下文、结束（含异常）清理
     */
    public static <T> Callable<T> wrap(TenantContext context, Callable<T> task) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");
        return () -> {
            try (TenantContextScope ignored = open(context)) {
                return task.call();
            }
        };
    }
}
