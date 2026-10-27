package com.flowdesk.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线程池复用与异步传播测试（ARCHITECTURE §7.2 异步线程、§10.3 线程池复用负测）。
 *
 * <p>必须证明：任务间不串租户、不依赖线程继承、显式快照包装在子线程可用且结束后清理。</p>
 */
class TenantContextThreadPoolTest {

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
     * 创建已验证 Broker 消息来源的测试上下文，供消费者线程复用及异常清理测试使用。
     */
    private static TenantContext brokerContext() {
        return TenantContext.ofValidatedBrokerMessage(
                TENANT_A, "flowdesk-ticket-service", CORR_1);
    }

    /**
     * 验证包装任务使用提交时上下文快照执行，并在结束后恢复工作线程状态。
     */
    @Test
    void wrappedTaskRunsWithSnapshotAndCleansUp() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<UUID> task = TenantContextScope.wrap(contextA(),
                    () -> TenantContextHolder.current().tenantId());

            Future<UUID> future = pool.submit(task);
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(TENANT_A);

            // 包装任务结束后，池线程无残留租户。
            Future<Boolean> next = pool.submit(() -> TenantContextHolder.currentOrNull() == null);
            assertThat(next.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证线程池复用同一工作线程时，上一个任务的租户不会泄露到后续任务。
     */
    @Test
    void threadPoolReuseDoesNotLeakTenantBetweenTasks() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 任务 1：在池线程内建立租户 A 上下文并完成（作用域清理）。
            Future<Void> first = pool.submit(() -> {
                try (TenantContextScope ignored = TenantContextScope.open(contextA())) {
                    assertThat(TenantContextHolder.current().tenantId()).isEqualTo(TENANT_A);
                }
                return null;
            });
            first.get(10, TimeUnit.SECONDS);

            // 任务 2：复用同一池线程，必须看不到租户 A（无串租户、无默认租户）。
            Future<Boolean> second = pool.submit(() -> TenantContextHolder.currentOrNull() == null);
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证同一线程池中不同租户任务各自使用正确上下文，避免并发混淆。
     */
    @Test
    void differentTenantsInSamePoolAreIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothStarted = new CountDownLatch(2);
            AtomicReference<String> tenantA = new AtomicReference<>();
            AtomicReference<String> tenantB = new AtomicReference<>();

            Future<Void> fA = pool.submit(() -> {
                try (TenantContextScope ignored = TenantContextScope.open(contextA())) {
                    bothStarted.countDown();
                    bothStarted.await(10, TimeUnit.SECONDS);
                    tenantA.set(TenantContextHolder.current().tenantId().toString());
                }
                return null;
            });
            Future<Void> fB = pool.submit(() -> {
                try (TenantContextScope ignored = TenantContextScope.open(contextB())) {
                    bothStarted.countDown();
                    bothStarted.await(10, TimeUnit.SECONDS);
                    tenantB.set(TenantContextHolder.current().tenantId().toString());
                }
                return null;
            });

            fA.get(10, TimeUnit.SECONDS);
            fB.get(10, TimeUnit.SECONDS);
            assertThat(tenantA.get()).isEqualTo(TENANT_A.toString());
            assertThat(tenantB.get()).isEqualTo(TENANT_B.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证可信租户上下文不会通过线程继承自动传播，异步任务须显式携带快照。
     */
    @Test
    void contextDoesNotFlowByThreadInheritance() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 主线程建立上下文，未显式包装的任务不得继承。
            try (TenantContextScope ignored = TenantContextScope.open(contextA())) {
                Future<Boolean> future = pool.submit(() -> TenantContextHolder.currentOrNull() == null);
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证包装任务抛异常时仍清理工作线程上下文。
     */
    @Test
    void wrappedTaskThrowingStillCleansUp() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Callable<Void> task = TenantContextScope.wrap(contextA(), () -> {
                throw new IllegalStateException("boom");
            });
            assertThatThrownBy(() -> {
                try {
                    pool.submit(task).get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(IllegalStateException.class);

            // 子线程任务异常结束后，池线程仍被清理。
            Future<Boolean> next = pool.submit(() -> TenantContextHolder.currentOrNull() == null);
            assertThat(next.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 验证消息处理异常不把生产者或租户泄露到复用的消费线程。
     */
    @Test
    void brokerMessageExceptionDoesNotLeakProducerOrTenantIntoReusedConsumerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> failedDelivery = pool.submit(() -> {
                try (TenantContextScope ignored = TenantContextScope.open(brokerContext())) {
                    TenantContext current = TenantContextHolder.current();
                    assertThat(current.source()).isEqualTo(TenantContextSource.BROKER_MESSAGE);
                    assertThat(current.actor()).isEqualTo(
                            new ActorRef.Service("flowdesk-ticket-service"));
                    throw new IllegalStateException("temporary consumer failure");
                }
            });

            assertThatThrownBy(() -> {
                try {
                    failedDelivery.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(IllegalStateException.class);

            Future<TenantContext> nextDelivery = pool.submit(TenantContextHolder::currentOrNull);
            assertThat(nextDelivery.get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
