package com.flowdesk.common.tenant;

import java.util.Objects;
/**
 * 可信租户上下文的来源类型（ARCHITECTURE §7.1 信任根与来源类型）。
 *
 * <p>来源类型用于说明 TenantContext 是经由哪条受信路径建立的；它不是客户端可编辑输入。
 * 禁止默认租户：上下文只能通过受信工厂建立，来源类型随之确定。</p>
 */
public enum TenantContextSource {

    /** 人类 HTTP：IAM 签发且密码学验证通过的 JWT 受保护声明。 */
    HUMAN_JWT,

    /** 服务间 HTTP：受认证服务身份 JWT（如 Ticket/Workflow → IAM 资格预检）。 */
    SERVICE_JWT,

    /** Webhook：Alert 完成全部验签与 nonce/eventId 单胜后从唯一 MonitoringIntegration 权威映射建立。 */
    WEBHOOK,

    /**
     * Broker 消息：消费者完成 Envelope、允许方向、租户与生产者服务校验后建立。
     * 该来源只携带已验证的生产者服务主体，不能由自报消息属性单独建立。
     */
    BROKER_MESSAGE,

    /** 后台任务/人工重放：从本服务 MySQL 持久化 TenantRef（SLA、Outbox、对账、恢复记录）恢复。 */
    PERSISTED_TASK;

    /**
     * 校验该来源是否允许携带人类主体。
     *
     * @return 是否人类主体来源
     */
    public boolean isHuman() {
        return this == HUMAN_JWT;
    }

    /**
     * 校验该来源是否为服务间 HTTP JWT；Broker 服务主体使用独立来源分类。
     *
     * @return 是否服务身份 JWT 来源
     */
    public boolean isService() {
        return this == SERVICE_JWT;
    }

    /**
     * 校验该来源是否允许携带集成主体。
     *
     * @return 是否集成主体来源
     */
    public boolean isIntegration() {
        return this == WEBHOOK;
    }

    /**
     * 校验该来源是否为已经过消费者入口守卫验证的 Broker 消息。
     *
     * @return 是否 Broker 消息来源
     */
    public boolean isBrokerMessage() {
        return this == BROKER_MESSAGE;
    }

    /**
     * 校验该来源是否允许携带持久化任务主体。
     *
     * @return 是否持久化任务来源
     */
    public boolean isPersistedTask() {
        return this == PERSISTED_TASK;
    }

    /**
     * 按来源建立对应的受信主体。
     *
     * @param actorRef 已验证的主体引用
     * @return 与来源匹配的主体引用
     * @throws IllegalArgumentException 主体类型与该来源不兼容
     */
    public ActorRef requireCompatible(ActorRef actorRef) {
        Objects.requireNonNull(actorRef, "actorRef");
        boolean compatible = switch (this) {
            case HUMAN_JWT -> actorRef instanceof ActorRef.Human;
            case SERVICE_JWT -> actorRef instanceof ActorRef.Service;
            case WEBHOOK -> actorRef instanceof ActorRef.Integration;
            case BROKER_MESSAGE -> actorRef instanceof ActorRef.Service;
            case PERSISTED_TASK -> true;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "ActorRef " + actorRef.getClass().getSimpleName()
                            + " is not compatible with TenantContextSource " + this);
        }
        return actorRef;
    }
}
