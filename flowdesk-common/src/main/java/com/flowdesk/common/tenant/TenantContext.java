package com.flowdesk.common.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次处理范围内的可信租户、操作者/服务主体和追踪上下文（ARCHITECTURE §7.1）。
 *
 * <p>它不是客户端可编辑的业务输入：没有默认租户，没有从请求头/body/query 构造的路径，
 * 只能通过受信工厂按已验证的来源建立。所有数据库查询/更新、Redis 命名空间、日志、审计
 * 和新 Outbox 事实必须使用同一 TenantContext；跨对象引用必须与当前 TenantContext 一致
 * （{@link TenantContexts#requireSameTenant}）。</p>
 *
 * <p>{@code correlationId} 仅用于追踪，不能成为 tenantId、用户身份或幂等依据。</p>
 *
 * <p>不可变；线程安全；只通过 {@link TenantContextHolder} / {@link TenantContextScope}
 * 在一次请求、已验证消息处理或持久化任务项的作用域内传播，离开作用域时无论成功或异常都清理。</p>
 */
public final class TenantContext {

    private final UUID tenantId;
    private final ActorRef actor;
    private final TenantContextSource source;
    private final UUID correlationId;

    private TenantContext(UUID tenantId, ActorRef actor, TenantContextSource source, UUID correlationId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.source = Objects.requireNonNull(source, "source");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    /**
     * 以人类 HTTP JWT 来源建立上下文（IAM 签发且密码学验证通过后）。
     *
     * @param tenantId      受保护声明中的权威租户标识
     * @param userId        受保护声明中的 IAM 权威用户标识
     * @param correlationId 追踪标识；不参与授权
     */
    public static TenantContext ofHumanJwt(UUID tenantId, UUID userId, UUID correlationId) {
        return new TenantContext(tenantId, ActorRef.human(userId), TenantContextSource.HUMAN_JWT, correlationId);
    }

    /**
     * 以服务身份 JWT 来源建立上下文（受认证调用服务，如 Ticket/Workflow → IAM 资格预检）。
     *
     * @param tenantId      受保护声明中的权威租户标识
     * @param serviceId     调用服务部署单元名
     * @param correlationId 追踪标识；不参与授权
     */
    public static TenantContext ofServiceJwt(UUID tenantId, String serviceId, UUID correlationId) {
        return new TenantContext(tenantId, ActorRef.service(serviceId), TenantContextSource.SERVICE_JWT, correlationId);
    }

    /**
     * 以 Webhook 来源建立上下文（Alert 完成全部验签与 nonce/eventId 单胜后）。
     *
     * @param tenantId      唯一通过验签的 MonitoringIntegration 权威映射租户
     * @param integrationId 唯一通过验签的 MonitoringIntegration 标识
     * @param correlationId 追踪标识；不参与授权
     */
    public static TenantContext ofWebhook(UUID tenantId, UUID integrationId, UUID correlationId) {
        return new TenantContext(tenantId, ActorRef.integration(integrationId), TenantContextSource.WEBHOOK, correlationId);
    }

    /**
     * 以已验证的 Broker 消息建立消费者上下文。
     *
     * <p>该工厂不是 Envelope guard。消费者必须先验证 Broker 允许方向、Topic、契约、
     * producer、tenant、消息身份、关联标识与 body 完整性，再把守卫得出的权威值传入。
     * 自报的 producer/tenant property 本身不能作为调用本工厂的充分依据。创建上下文也不会
     * 自动安装线程状态；消费者仍须用 {@link TenantContextScope#open(TenantContext)} 显式限定
     * 单次消息处理作用域。</p>
     *
     * @param tenantId         经完整消息守卫验证的权威租户标识
     * @param producerServiceId 经允许方向与 Broker 身份边界验证的生产者服务标识
     * @param correlationId    原消息 Envelope 中经格式验证的关联标识；只用于追踪
     * @return 尚未安装到线程的不可变消息处理上下文
     */
    public static TenantContext ofValidatedBrokerMessage(UUID tenantId,
                                                         String producerServiceId,
                                                         UUID correlationId) {
        TenantContextSource source = TenantContextSource.BROKER_MESSAGE;
        ActorRef actor = ActorRef.service(producerServiceId);
        return new TenantContext(tenantId, source.requireCompatible(actor), source, correlationId);
    }

    /**
     * 以持久化任务/人工重放来源建立上下文（从本服务 MySQL 持久化 TenantRef 恢复后）。
     *
     * @param tenantId      持久化事实中的权威租户标识
     * @param actor         已验证的操作者/服务主体
     * @param correlationId 本次工作项独立追踪标识；不参与授权
     * @throws IllegalArgumentException 主体与持久化任务来源不兼容
     */
    public static TenantContext ofPersistedTask(UUID tenantId, ActorRef actor, UUID correlationId) {
        TenantContextSource source = TenantContextSource.PERSISTED_TASK;
        return new TenantContext(tenantId, source.requireCompatible(actor), source, correlationId);
    }

    /** 权威租户标识；所有本作用域的数据库/缓存/审计事实使用它。 */
    public UUID tenantId() {
        return tenantId;
    }

    /** 已验证的操作者/服务主体引用。 */
    public ActorRef actor() {
        return actor;
    }

    /** 上下文建立的信任根来源类型。 */
    public TenantContextSource source() {
        return source;
    }

    /** 追踪标识；只用于追踪，不参与身份、租户、权限或幂等。 */
    public UUID correlationId() {
        return correlationId;
    }

    /**
     * 校验该上下文是否与给定租户一致（跨对象引用必须与当前 TenantContext 一致）。
     *
     * @param tenantId 期望的租户标识
     * @throws TenantContextMismatchException 不一致时抛出
     */
    public void requireTenant(UUID tenantId) {
        TenantContexts.requireSameTenant(this, tenantId);
    }

    @Override
    public String toString() {
        return "TenantContext{tenantId=" + tenantId
                + ", source=" + source
                + ", actor=" + actor
                + ", correlationId=" + correlationId + '}';
    }
}
