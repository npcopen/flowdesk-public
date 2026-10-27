package com.flowdesk.notification.service.impl;
import com.flowdesk.notification.model.*;

import com.flowdesk.notification.service.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.notification.NotificationWireCodec;
import com.flowdesk.notification.dto.NotificationDtos;
import com.flowdesk.notification.mapper.NotificationAuditMapper;
import com.flowdesk.notification.mapper.NotificationMapper;
import com.flowdesk.notification.model.NotificationRow;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 处理当前用户通知查询与读状态命令，以收件人校验、乐观锁和幂等审计保护写入。 */
@Service
public class NotificationServiceImpl implements NotificationService {
    private final NotificationMapper repository;
    private final NotificationAuditMapper auditMapper;
    private final NotificationAuditWriter audits;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NotificationCursorTokenService cursors;

    /**
     * 注入通知查询、审计映射与幂等审计服务，读状态修改由运行时事务原子提交；时钟和签名游标统一时间及分页边界。
     */
    public NotificationServiceImpl(NotificationMapper repository,
            @Qualifier("notificationAuditMapper") NotificationAuditMapper auditMapper,
            NotificationAuditWriter audits,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            Clock clock, NotificationCursorTokenService cursors) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.auditMapper = Objects.requireNonNull(auditMapper, "auditMapper");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /**
     * 按租户与接收人分页读取通知，将筛选条件绑定到签名游标并生成下一页边界。
     */
    public Page<NotificationDtos.NotificationResponse> list(String state, String kind,
                                                            Instant from, Instant to,
                                                            int pageSize, String pageToken,
                                                            String sort) {
        NotificationSupport.require(PermissionCode.NOTIFICATION_READ_SELF);
        if (state != null && !Set.of("UNREAD", "READ").contains(state)) {
            throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "state 无效。");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw com.flowdesk.common.web.ApiException.badRequest("INVALID_QUERY_PARAMETER", "时间范围无效。");
        }
        String normalized = sort == null || sort.isBlank() ? "-createdAt" : sort;
        if (!Set.of("createdAt", "-createdAt").contains(normalized)) {
            throw com.flowdesk.common.web.ApiException.badRequest("SORT_UNSUPPORTED", "排序方式不受支持。");
        }
        boolean ascending = !normalized.startsWith("-");
        String scope = "notifications|" + state + "|" + kind + "|" + from + "|" + to;
        NotificationCursorTokenService.Cursor cursor = cursors.decode(pageToken,
                NotificationSupport.context().tenantId(), scope, pageSize, normalized);
        List<NotificationRow> rows = repository.listNotifications(
                NotificationSupport.context().tenantId(), state, kind, from, to, ascending,
                cursor == null ? null : cursor.value(), cursor == null ? null : cursor.id(), pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) rows = rows.subList(0, pageSize);
        String next = null;
        if (more && !rows.isEmpty()) {
            var last = rows.get(rows.size() - 1);
            next = cursors.encode(NotificationSupport.context().tenantId(), scope, pageSize,
                    normalized, last.createdAt(), last.notificationId());
        }
        return new Page<>(rows.stream().map(NotificationServiceImpl::response).toList(), next, normalized);
    }

    /**
     * 仅统计当前租户内当前用户的未读通知，避免角标泄露其他接收人的事实。
     */
    public NotificationDtos.UnreadCountResponse unreadCount() {
        NotificationSupport.require(PermissionCode.NOTIFICATION_READ_SELF);
        Instant now = now();
        return new NotificationDtos.UnreadCountResponse(repository.countUnread(
                NotificationSupport.context().tenantId(), NotificationSupport.actorId()), now);
    }

    /** 先识别已成功的同一命令，再核对 ETag；事务内重新检查审计与版本，防止并发重复写入。 */
    public ReadStateResult updateReadState(UUID notificationId, NotificationDtos.ReadStateWrite request,
                                           String ifMatch, String idempotencyKey) {
        NotificationSupport.require(PermissionCode.NOTIFICATION_WRITE_SELF);
        String key = NotificationSupport.idempotency(idempotencyKey);
        if (request == null || request.state() == null) {
            throw com.flowdesk.common.web.ApiException.unprocessable("VALIDATION_FAILED", "state 必填。", List.of());
        }
        UUID tenant = NotificationSupport.context().tenantId();
        UUID actor = NotificationSupport.actorId();
        // 幂等审计绑定通知及目标读状态，同一键改写其他状态会被识别为不同业务意图。
        String canonical = "notificationId=" + notificationId + "|state=" + request.state().name();
        byte[] effectKey = NotificationWireCodec.deriveKey("FD-NOTIFICATION-READ-V1", tenant,
                actor, notificationId, key);
        UUID auditId = stableUuid(effectKey);
        Optional<NotificationAuditRow> prior = auditMapper.findAudit(tenant, auditId).stream().findFirst();
        if (prior.isPresent()) {
            validateReadAudit(prior.get(), auditId, notificationId, actor, canonical);
            NotificationRow row = repository.findNotification(tenant, notificationId, false)
                    .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
            return new ReadStateResult(response(row), true);
        }
        NotificationRow visible = repository.findNotification(tenant, notificationId, false)
                .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
        long expected = NotificationSupport.ifMatch(ifMatch);
        if (visible.lockVersion() != expected) {
            throw new com.flowdesk.common.web.ApiException(HttpStatus.PRECONDITION_FAILED,
                    "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false, List.of(),
                    java.util.Map.of(org.springframework.http.HttpHeaders.ETAG,
                            "\"lv:" + visible.lockVersion() + "\""));
        }
        Instant now = now();
        ReadStateResult result = transactions.execute(tx -> {
            NotificationRow locked = repository.findNotification(tenant, notificationId, true)
                    .filter(value -> actor.equals(value.recipientUserId())).orElseThrow(this::opaqueNotFound);
            Optional<NotificationAuditRow> raced = auditMapper.findAudit(tenant, auditId).stream().findFirst();
            if (raced.isPresent()) {
                validateReadAudit(raced.get(), auditId, notificationId, actor, canonical);
                return new ReadStateResult(response(locked), true);
            }
            if (locked.lockVersion() != expected) {
                throw new com.flowdesk.common.web.ApiException(HttpStatus.PRECONDITION_FAILED,
                        "RESOURCE_VERSION_CONFLICT", "资源已更新，请刷新后重试。", false, List.of(),
                        java.util.Map.of(org.springframework.http.HttpHeaders.ETAG,
                                "\"lv:" + locked.lockVersion() + "\""));
            }
            String target = request.state().name();
            if (!target.equals(locked.state())) {
                String newState = target;
                int changed = repository.updateReadState(tenant, notificationId, actor, expected,
                        newState, "READ".equals(newState) ? now : null, now);
                // 行锁与乐观版本共同保护写入；状态确实变化时必须恰好更新一行，不能把 CAS 失败记录为幂等成功。
                if (changed != 1) {
                    throw new IllegalStateException("Notification read-state CAS changed " + changed + " rows");
                }
                locked = repository.findNotification(tenant, notificationId, false).orElseThrow();
            }
            audits.insertOnce(tenant, auditId, "NOTIFICATION_READ_STATE", "NOTIFICATION",
                    notificationId, "SUCCEEDED", "USER", actor, canonical, null,
                    NotificationSupport.context().correlationId(), null, now);
            return new ReadStateResult(response(locked), false);
        });
        return Objects.requireNonNull(result);
    }

    /**
     * 核对既有读状态审计的目标、操作者和意图，防止幂等键被用于另一请求。
     */
    private static void validateReadAudit(NotificationAuditRow audit, UUID auditId,
                                          UUID notificationId, UUID actor, String canonical) {
        if (!auditId.equals(audit.auditId()) || !"NOTIFICATION_READ_STATE".equals(audit.actionCode())
                || !"NOTIFICATION".equals(audit.targetType()) || !notificationId.equals(audit.targetId())
                || !"SUCCEEDED".equals(audit.outcome()) || !"USER".equals(audit.actorType())
                || !actor.equals(audit.actorId()) || !canonical.equals(audit.reason())) {
            throw com.flowdesk.common.web.ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 Idempotency-Key 已用于不同业务意图。");
        }
    }

    /**
     * 统一表达通知不存在或不属于当前用户，避免泄露他人通知是否存在。
     */
    private com.flowdesk.common.web.ApiException opaqueNotFound() {
        return com.flowdesk.common.web.ApiException.notFound("NOTIFICATION_RECIPIENT_ONLY", "通知不存在或不属于当前用户。");
    }

    /**
     * 读取注入时钟并截断至数据库可保存的精度，便于持久化比较及测试。
     */
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    /**
     * 从稳定摘要生成 UUID，防止重复处理同一业务意图时创建新的记录身份。
     */
    private static UUID stableUuid(byte[] key) {
        byte[] first = Arrays.copyOf(key, 16);
        return ByteBufferHelper.uuid(first);
    }

    /**
     * 将持久化事实映射为接口响应，仅输出当前契约定义的业务字段。
     */
    private static NotificationDtos.NotificationResponse response(NotificationRow row) {
        String domain = row.sourceService().contains("workflow") ? "WORKFLOW" : "TICKET";
        return new NotificationDtos.NotificationResponse(row.notificationId(), row.notificationKind(),
                domain, row.sourceRefType(), row.sourceRefId(), row.title(), row.body(), row.state(),
                row.readAt(), row.createdAt(), row.updatedAt(), row.lockVersion());
    }


    /**
     * 标识字节转换工具，保持摘要字节与 UUID 高低位编码一致。
     */
    private static final class ByteBufferHelper {
        /**
         * 在 UUID 与持久化表示之间转换，保持标识高低位的编码顺序一致。
         */
        static UUID uuid(byte[] value) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
