package com.flowdesk.alert.dto;

import com.flowdesk.common.web.AsyncProgress;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 告警 HTTP 接口的数据视图，集中定义对外字段，不承担数据库读写或状态推进。
 *
 * <p>WebhookReceipt 表达一次请求的接纳结果，AlertEventView 表达单个外部事件，
 * IncidentView 表达同一周期聚合后的故障，IncidentTicketLinkView 表达异步建单进度。
 * 原始事件与当前故障是不同层次：后者可能继续变化，前者保存接纳时的事实。
 */
public final class AlertDtos {
    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private AlertDtos() {}

    /** disposition 说明本次事件如何归类；async 说明后续任务，不代表这些任务已经完成。 */
    public record WebhookReceipt(
            UUID alertEventId,
            String eventId,
            String disposition,
            UUID incidentId,
            String incidentStatus,
            String rawBodySha256,
            Instant receivedAt,
            List<AsyncProgress> async) {}

    /**
     * 响应中的告警集成引用，只展示集成标识、代码和名称，避免暴露认证凭据与签名密钥。
     */
    public record IntegrationRef(
            UUID integrationId, String integrationCode, String integrationName) {}

    /** occurredAt 是监控源报告时间，receivedAt 是本服务接纳时间；二者不能混用。 */
    public record AlertEventView(
            UUID alertEventId,
            IntegrationRef integrationRef,
            String externalEventId,
            String eventType,
            String fingerprint,
            String cycleRef,
            Instant occurredAt,
            Instant receivedAt,
            String disposition,
            UUID incidentId,
            String rawBodySha256,
            String summary,
            UUID correlationId) {}

    /** generationNo 区分同一指纹的不同故障代次；eventCount 是该故障聚合的事件数。 */
    public record IncidentView(
            UUID incidentId,
            String fingerprint,
            int generationNo,
            String status,
            int eventCount,
            Instant openedAt,
            Instant lastEventAt,
            Instant recoveredAt,
            String summary,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {}

    /** status 表达建单进度，ticketId 仅在关联建立后存在；故障恢复状态另见 IncidentView。 */
    public record IncidentTicketLinkView(
            UUID incidentId,
            String status,
            UUID ticketId,
            Instant requestedAt,
            Instant establishedAt,
            Instant recoveryRequiredAt,
            String recoveryReasonCode,
            int retryCount,
            Instant lastAttemptAt,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {}
}
