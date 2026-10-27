package com.flowdesk.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantContextScope 作用域测试（ARCHITECTURE §7.1.2/7.1.3）。
 *
 * <p>覆盖：作用域内可读取、正常退出清理、异常路径清理（finally 语义）、
 * 嵌套作用域恢复、缺失上下文无默认租户。</p>
 */
class TenantContextScopeTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CORR_1 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /**
     * 创建租户 A 的可信测试上下文，供作用域可见性及线程隔离断言使用。
     */
    private static TenantContext contextA() {
        return TenantContext.ofHumanJwt(TENANT_A, USER_1, CORR_1);
    }

    /**
     * 创建租户 B 的可信测试上下文，验证嵌套切换及线程复用时不会误用租户 A。
     */
    private static TenantContext contextB() {
        return TenantContext.ofHumanJwt(TENANT_B, USER_1, CORR_1);
    }

    /**
     * 验证打开作用域后当前线程可读取该租户上下文。
     */
    @Test
    void contextIsVisibleInsideScope() {
        TenantContext context = contextA();
        try (TenantContextScope scope = TenantContextScope.open(context)) {
            assertThat(TenantContextHolder.current()).isSameAs(context);
            assertThat(scope.context()).isSameAs(context);
        }
    }

    /**
     * 验证作用域正常关闭时清除当前上下文，防止后续任务继承旧租户。
     */
    @Test
    void contextIsClearedOnNormalExit() {
        try (TenantContextScope ignored = TenantContextScope.open(contextA())) {
            assertThat(TenantContextHolder.current()).isNotNull();
        }
        assertThatThrownBy(TenantContextHolder::current).isInstanceOf(NoTenantContextException.class);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证作用域内抛出异常后仍正确清理上下文。
     */
    @Test
    void contextIsClearedOnExceptionExit() {
        assertThatThrownBy(() -> {
            try (TenantContextScope ignored = TenantContextScope.open(contextA())) {
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);

        // 异常路径也必须清理：线程/请求结束后不得残留租户。
        assertThatThrownBy(TenantContextHolder::current).isInstanceOf(NoTenantContextException.class);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证嵌套作用域关闭后恢复外层上下文，最终退出时不残留租户。
     */
    @Test
    void nestedScopeRestoresOuterOnClose() {
        try (TenantContextScope outer = TenantContextScope.open(contextA())) {
            try (TenantContextScope inner = TenantContextScope.open(contextB())) {
                assertThat(TenantContextHolder.current().tenantId()).isEqualTo(TENANT_B);
            }
            // 内层关闭后恢复外层。
            assertThat(TenantContextHolder.current().tenantId()).isEqualTo(TENANT_A);
        }
        assertThatThrownBy(TenantContextHolder::current).isInstanceOf(NoTenantContextException.class);
    }

    /**
     * 验证作用域外不存在默认租户，未认证调用不能回退到隐式租户。
     */
    @Test
    void noDefaultTenantOutsideScope() {
        // 禁止默认租户：没有任何路径能读到猜测的默认上下文。
        assertThatThrownBy(TenantContextHolder::current).isInstanceOf(NoTenantContextException.class);
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    /**
     * 验证不能用 null 上下文打开可信租户作用域。
     */
    @Test
    void openRejectsNullContext() {
        assertThatThrownBy(() -> TenantContextScope.open(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证重复关闭同一作用域不会误清理或恢复错误上下文。
     */
    @Test
    void closeIsIdempotent() {
        TenantContextScope scope = TenantContextScope.open(contextA());
        scope.close();
        scope.close(); // 第二次 close 不得抛异常
        assertThatThrownBy(TenantContextHolder::current).isInstanceOf(NoTenantContextException.class);
    }
}
