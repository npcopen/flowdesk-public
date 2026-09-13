package com.flowdesk.ticket.repository;

import com.flowdesk.common.tenant.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ticket persistence. Every statement is tenant-scoped first; all mutating updates
 * carry the authoritative lock_version predicate.
 */
@Repository
public class TicketRepository {

    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ----- service catalog -------------------------------------------------------

    public List<CatalogItemRow> findCatalogItems(UUID tenantId, String status, String riskLevel,
                                                 String sortColumn, boolean ascending,
                                                 String cursorValue, UUID cursorId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT catalog_item_id, item_code, item_name, description, risk_level,
                       requires_approval, workflow_definition_id, workflow_version_id,
                       workflow_version_no, sla_policy_id, status, lock_version,
                       created_at, updated_at
                FROM service_catalog_item
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (riskLevel != null) {
            sql.append(" AND risk_level = ?");
            args.add(riskLevel);
        }
        if (cursorValue != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            String column = catalogSortColumn(sortColumn);
            sql.append(" AND (").append(column).append(" ").append(cmp).append(" ? OR (")
                    .append(column).append(" = ? AND catalog_item_id ").append(cmp).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY ").append(catalogSortColumn(sortColumn))
                .append(ascending ? " ASC" : " DESC")
                .append(", catalog_item_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), CATALOG_ROW_MAPPER, args.toArray());
    }

    public Optional<CatalogItemRow> findCatalogItem(UUID tenantId, UUID catalogItemId) {
        return jdbc.query("""
                SELECT catalog_item_id, item_code, item_name, description, risk_level,
                       requires_approval, workflow_definition_id, workflow_version_id,
                       workflow_version_no, sla_policy_id, status, lock_version,
                       created_at, updated_at
                FROM service_catalog_item
                WHERE tenant_id = ? AND catalog_item_id = ?
                """, CATALOG_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(catalogItemId))
                .stream().findFirst();
    }

    public Optional<CatalogItemRow> findCatalogItemForUpdate(UUID tenantId, UUID catalogItemId) {
        return jdbc.query("""
                SELECT catalog_item_id, item_code, item_name, description, risk_level,
                       requires_approval, workflow_definition_id, workflow_version_id,
                       workflow_version_no, sla_policy_id, status, lock_version,
                       created_at, updated_at
                FROM service_catalog_item
                WHERE tenant_id = ? AND catalog_item_id = ? FOR UPDATE
                """, CATALOG_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(catalogItemId))
                .stream().findFirst();
    }

    public int updateCatalogItem(UUID tenantId, UUID catalogItemId, long expectedLockVersion,
                                 String itemName, String description, boolean descriptionPresent,
                                 UUID slaPolicyId,
                                 String status, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE service_catalog_item
                SET item_name = COALESCE(?, item_name),
                    description = CASE WHEN ? THEN ? ELSE description END,
                    sla_policy_id = COALESCE(?, sla_policy_id),
                    status = COALESCE(?, status),
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND catalog_item_id = ? AND lock_version = ?
                """, itemName, descriptionPresent, description, Uuids.toBytes(slaPolicyId), status,
                Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(catalogItemId), expectedLockVersion);
    }

    public void insertCatalogConfiguration(UUID tenant, UUID id, com.flowdesk.ticket.api.CatalogWriteRequest r, UUID actor, Instant now) {
        var w=r.workflowRef();
        jdbc.update("""
                INSERT INTO service_catalog_item (tenant_id,catalog_item_id,item_code,item_name,description,
                  risk_level,requires_approval,workflow_definition_id,workflow_version_id,workflow_version_no,
                  sla_policy_id,status,lock_version,created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,'USER',?,?,'USER',?)
                """,Uuids.toBytes(tenant),Uuids.toBytes(id),r.itemCode(),r.itemName(),r.description(),r.riskLevel().name(),
                w!=null,w==null?null:Uuids.toBytes(w.definitionId()),w==null?null:Uuids.toBytes(w.workflowVersionId()),w==null?null:w.versionNo(),
                Uuids.toBytes(r.slaPolicyId()),r.status().name(),Timestamp.from(now),Uuids.toBytes(actor),Timestamp.from(now),Uuids.toBytes(actor));
    }

    public int updateCatalogConfiguration(UUID tenant, UUID id, com.flowdesk.ticket.api.CatalogWriteRequest r, long version, UUID actor, Instant now) {
        var w=r.workflowRef();
        return jdbc.update("""
                UPDATE service_catalog_item SET item_name=?,description=?,risk_level=?,requires_approval=?,
                  workflow_definition_id=?,workflow_version_id=?,workflow_version_no=?,sla_policy_id=?,status=?,
                  lock_version=lock_version+1,updated_at=?,updated_by_type='USER',updated_by_id=?
                WHERE tenant_id=? AND catalog_item_id=? AND lock_version=?
                """,r.itemName(),r.description(),r.riskLevel().name(),w!=null,w==null?null:Uuids.toBytes(w.definitionId()),
                w==null?null:Uuids.toBytes(w.workflowVersionId()),w==null?null:w.versionNo(),Uuids.toBytes(r.slaPolicyId()),r.status().name(),
                Timestamp.from(now),Uuids.toBytes(actor),Uuids.toBytes(tenant),Uuids.toBytes(id),version);
    }

    // ----- SLA policies ------------------------------------------------------------

    public Optional<SlaPolicyRow> findSlaPolicy(UUID tenantId, UUID slaPolicyId) {
        return jdbc.query("""
                SELECT sla_policy_id, policy_code, policy_name, first_response_seconds,
                       resolution_seconds, first_response_reminder_before_seconds,
                       resolution_reminder_before_seconds, status, lock_version
                FROM sla_policy
                WHERE tenant_id = ? AND sla_policy_id = ?
                """, SLA_POLICY_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(slaPolicyId))
                .stream().findFirst();
    }

    public Optional<SlaPolicyRow> findActiveSlaPolicy(UUID tenantId, UUID slaPolicyId) {
        return jdbc.query("""
                SELECT sla_policy_id, policy_code, policy_name, first_response_seconds,
                       resolution_seconds, first_response_reminder_before_seconds,
                       resolution_reminder_before_seconds, status, lock_version
                FROM sla_policy
                WHERE tenant_id = ? AND sla_policy_id = ? AND status = 'ACTIVE'
                """, SLA_POLICY_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(slaPolicyId))
                .stream().findFirst();
    }

    public List<SlaPolicyRow> findSlaPolicies(UUID tenantId, String status, String sortColumn,
                                              boolean ascending, String cursorValue, UUID cursorId,
                                              int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT sla_policy_id, policy_code, policy_name, first_response_seconds,
                       resolution_seconds, first_response_reminder_before_seconds,
                       resolution_reminder_before_seconds, status, lock_version
                FROM sla_policy
                WHERE tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (cursorValue != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            String column = slaSortColumn(sortColumn);
            sql.append(" AND (").append(column).append(" ").append(cmp).append(" ? OR (")
                    .append(column).append(" = ? AND sla_policy_id ").append(cmp).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY ").append(slaSortColumn(sortColumn))
                .append(ascending ? " ASC" : " DESC")
                .append(", sla_policy_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), SLA_POLICY_MAPPER, args.toArray());
    }

    // ----- ticket ----------------------------------------------------------------

    public long countPendingTickets(UUID tenantId, Scope scope, UUID actorId) {
        String visibility = switch (scope) {
            case REQUESTER -> " AND requester_user_id = ?";
            case ASSIGNEE -> " AND assignee_user_id = ?";
            case VISIBLE -> " AND (requester_user_id = ? OR assignee_user_id = ?)";
            case TENANT -> "";
        };
        String sql = "SELECT COUNT(*) FROM ticket WHERE tenant_id = ?"
                + " AND status IN ('AWAITING_APPROVAL','PENDING_ASSIGNMENT','ASSIGNED','IN_PROGRESS')"
                + visibility;
        return jdbc.queryForObject(sql, Long.class, scope == Scope.TENANT
                ? new Object[]{Uuids.toBytes(tenantId)}
                : scope == Scope.VISIBLE ? new Object[]{Uuids.toBytes(tenantId), Uuids.toBytes(actorId), Uuids.toBytes(actorId)}
                : new Object[]{Uuids.toBytes(tenantId), Uuids.toBytes(actorId)});
    }

    public Optional<TicketRow> findTicket(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT t.ticket_id, t.ticket_no, t.creation_intent_key, t.origin_type,
                       t.catalog_item_id, t.requester_user_id, t.previous_attempt_ticket_id,
                       t.subject, t.description, t.status, t.assignee_user_id,
                       t.workflow_definition_id, t.workflow_version_id, t.workflow_version_no,
                       t.first_processable_at, t.resolved_at, t.closed_at, t.last_reopened_at,
                       t.reopen_count, t.lock_version, t.created_at, t.updated_at
                FROM ticket t
                WHERE t.tenant_id = ? AND t.ticket_id = ?
                """, TICKET_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId))
                .stream().findFirst();
    }

    public Optional<TicketRow> findTicketByNumber(UUID tenantId, String ticketNo) {
        return jdbc.query("""
                SELECT t.ticket_id, t.ticket_no, t.creation_intent_key, t.origin_type,
                       t.catalog_item_id, t.requester_user_id, t.previous_attempt_ticket_id,
                       t.subject, t.description, t.status, t.assignee_user_id,
                       t.workflow_definition_id, t.workflow_version_id, t.workflow_version_no,
                       t.first_processable_at, t.resolved_at, t.closed_at, t.last_reopened_at,
                       t.reopen_count, t.lock_version, t.created_at, t.updated_at
                FROM ticket t
                WHERE t.tenant_id = ? AND t.ticket_no = ?
                """, TICKET_ROW_MAPPER, Uuids.toBytes(tenantId), ticketNo)
                .stream().findFirst();
    }

    public Optional<TicketRow> findTicketForUpdate(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT t.ticket_id, t.ticket_no, t.creation_intent_key, t.origin_type,
                       t.catalog_item_id, t.requester_user_id, t.previous_attempt_ticket_id,
                       t.subject, t.description, t.status, t.assignee_user_id,
                       t.workflow_definition_id, t.workflow_version_id, t.workflow_version_no,
                       t.first_processable_at, t.resolved_at, t.closed_at, t.last_reopened_at,
                       t.reopen_count, t.lock_version, t.created_at, t.updated_at
                FROM ticket t
                WHERE t.tenant_id = ? AND t.ticket_id = ? FOR UPDATE
                """, TICKET_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId))
                .stream().findFirst();
    }

    public Optional<TicketRow> findTicketByCreationIntent(UUID tenantId, byte[] creationIntentKey) {
        return jdbc.query("""
                SELECT t.ticket_id, t.ticket_no, t.creation_intent_key, t.origin_type,
                       t.catalog_item_id, t.requester_user_id, t.previous_attempt_ticket_id,
                       t.subject, t.description, t.status, t.assignee_user_id,
                       t.workflow_definition_id, t.workflow_version_id, t.workflow_version_no,
                       t.first_processable_at, t.resolved_at, t.closed_at, t.last_reopened_at,
                       t.reopen_count, t.lock_version, t.created_at, t.updated_at
                FROM ticket t
                WHERE t.tenant_id = ? AND t.creation_intent_key = ?
                """, TICKET_ROW_MAPPER, Uuids.toBytes(tenantId), creationIntentKey)
                .stream().findFirst();
    }

    /**
     * Atomically advances the tenant/day counter on the transaction-bound JDBC connection.
     * The row lock remains held until the surrounding Ticket transaction commits or rolls back,
     * so multiple service instances cannot allocate the same sequence value.
     */
    public long nextTicketSequence(UUID tenantId, LocalDate businessDate, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ticket number allocation requires an active transaction");
        }
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO ticket_number_counter
                  (tenant_id, business_date, sequence_value)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE
                  sequence_value = sequence_value + 1,
                  updated_at = ?
                """, Uuids.toBytes(tenantId), Date.valueOf(businessDate), timestamp);
        Long value = jdbc.queryForObject("""
                SELECT sequence_value
                FROM ticket_number_counter
                WHERE tenant_id = ? AND business_date = ?
                FOR UPDATE
                """, Long.class, Uuids.toBytes(tenantId), Date.valueOf(businessDate));
        if (value == null || value <= 0) {
            throw new IllegalStateException("Ticket number counter did not return a positive value");
        }
        return value;
    }

    public void insertTicket(UUID tenantId, UUID ticketId, String ticketNo, byte[] creationIntentKey,
                             String originType, UUID catalogItemId, UUID requesterUserId,
                             UUID previousAttemptTicketId, String subject, String description,
                             String status, UUID assigneeUserId, UUID workflowDefinitionId,
                             UUID workflowVersionId, Integer workflowVersionNo,
                             Instant firstProcessableAt, UUID actorId, UUID correlationId,
                             Instant now) {
        String actorType = "MANUAL".equals(originType) ? "USER" : "SERVICE";
        byte[] actorIdBytes = "MANUAL".equals(originType) ? Uuids.toBytes(actorId) : null;
        jdbc.update("""
                INSERT INTO ticket
                  (tenant_id, ticket_id, ticket_no, creation_intent_key, origin_type,
                   catalog_item_id, requester_user_id, previous_attempt_ticket_id,
                   subject, description, status, assignee_user_id,
                   workflow_definition_id, workflow_version_id, workflow_version_no,
                   first_processable_at, resolved_at, closed_at, last_reopened_at,
                   reopen_count, terminal_at, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL,
                        0, NULL, 0, ?, ?, ?, ?, ?, ?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), ticketNo, creationIntentKey,
                originType, Uuids.toBytes(catalogItemId), Uuids.toBytes(requesterUserId),
                Uuids.toBytes(previousAttemptTicketId), subject, description, status,
                Uuids.toBytes(assigneeUserId), Uuids.toBytes(workflowDefinitionId),
                Uuids.toBytes(workflowVersionId), workflowVersionNo,
                firstProcessableAt == null ? null : Timestamp.from(firstProcessableAt),
                Timestamp.from(now),
                actorType, actorIdBytes,
                Timestamp.from(now), actorType, actorIdBytes);
    }

    public int updateTicketAssign(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                  UUID assigneeUserId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'ASSIGNED',
                    assignee_user_id = ?,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Uuids.toBytes(assigneeUserId), Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), expectedLockVersion);
    }

    public int updateTicketAccept(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                  UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'IN_PROGRESS',
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), expectedLockVersion);
    }

    public int updateTicketTransfer(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                    UUID newAssigneeUserId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'ASSIGNED',
                    assignee_user_id = ?,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Uuids.toBytes(newAssigneeUserId), Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), expectedLockVersion);
    }

    public int updateTicketResolve(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                   UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'RESOLVED',
                    resolved_at = COALESCE(resolved_at, ?),
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), expectedLockVersion);
    }

    public int updateTicketClose(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                 UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'CLOSED',
                    closed_at = ?,
                    terminal_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                Uuids.toBytes(actorId), Uuids.toBytes(tenantId), Uuids.toBytes(ticketId),
                expectedLockVersion);
    }

    public int updateTicketReopen(UUID tenantId, UUID ticketId, long expectedLockVersion,
                                  UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE ticket
                SET status = 'IN_PROGRESS',
                    last_reopened_at = ?,
                    reopen_count = reopen_count + 1,
                    lock_version = lock_version + 1,
                    updated_at = ?,
                    updated_by_type = 'USER',
                    updated_by_id = ?
                WHERE tenant_id = ? AND ticket_id = ? AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), expectedLockVersion);
    }

    public List<TicketRow> findTickets(UUID tenantId, Scope scope, UUID scopeId, String status,
                                       String originType, UUID catalogItemId, Instant createdFrom,
                                       Instant createdTo, String sortColumn, boolean ascending,
                                       Object cursorValue, UUID cursorId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.ticket_id, t.ticket_no, t.creation_intent_key, t.origin_type,
                       t.catalog_item_id, t.requester_user_id, t.previous_attempt_ticket_id,
                       t.subject, t.description, t.status, t.assignee_user_id,
                       t.workflow_definition_id, t.workflow_version_id, t.workflow_version_no,
                       t.first_processable_at, t.resolved_at, t.closed_at, t.last_reopened_at,
                       t.reopen_count, t.lock_version, t.created_at, t.updated_at
                FROM ticket t
                WHERE t.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (scope == Scope.REQUESTER) {
            sql.append(" AND t.requester_user_id = ?");
            args.add(Uuids.toBytes(scopeId));
        } else if (scope == Scope.ASSIGNEE) {
            sql.append(" AND t.assignee_user_id = ?");
            args.add(Uuids.toBytes(scopeId));
        } else if (scope == Scope.VISIBLE) {
            sql.append(" AND (t.requester_user_id = ? OR t.assignee_user_id = ?)");
            args.add(Uuids.toBytes(scopeId));
            args.add(Uuids.toBytes(scopeId));
        }
        if (status != null) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        if (originType != null) {
            sql.append(" AND t.origin_type = ?");
            args.add(originType);
        }
        if (catalogItemId != null) {
            sql.append(" AND t.catalog_item_id = ?");
            args.add(Uuids.toBytes(catalogItemId));
        }
        if (createdFrom != null) {
            sql.append(" AND t.created_at >= ?");
            args.add(Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            sql.append(" AND t.created_at <= ?");
            args.add(Timestamp.from(createdTo));
        }
        String column = ticketSortColumn(sortColumn);
        if (cursorValue != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            sql.append(" AND (").append(column).append(" ").append(cmp).append(" ? OR (")
                    .append(column).append(" = ? AND t.ticket_id ").append(cmp).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY ").append(column)
                .append(ascending ? " ASC" : " DESC")
                .append(", t.ticket_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), TICKET_ROW_MAPPER, args.toArray());
    }

    // ----- comments ----------------------------------------------------------------

    public void insertComment(UUID tenantId, UUID commentId, UUID ticketId, String body,
                              UUID correlationId, UUID authorId, Instant now) {
        jdbc.update("""
                INSERT INTO ticket_comment
                  (tenant_id, comment_id, ticket_id, body, correlation_id,
                   created_at, created_by_type, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, 'USER', ?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(commentId), Uuids.toBytes(ticketId),
                body, Uuids.toBytes(correlationId), Timestamp.from(now), Uuids.toBytes(authorId));
    }

    public Optional<CommentRow> findComment(UUID tenantId, UUID commentId) {
        return jdbc.query("""
                SELECT comment_id, ticket_id, body, created_by_id, created_at, correlation_id
                FROM ticket_comment
                WHERE tenant_id = ? AND comment_id = ?
                """, COMMENT_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(commentId))
                .stream().findFirst();
    }

    public List<CommentRow> findComments(UUID tenantId, UUID ticketId, boolean ascending,
                                         Object cursorValue, UUID cursorId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT comment_id, ticket_id, body, created_by_id, created_at, correlation_id
                FROM ticket_comment
                WHERE tenant_id = ? AND ticket_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.add(Uuids.toBytes(ticketId));
        if (cursorValue != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            sql.append(" AND (created_at ").append(cmp).append(" ? OR (created_at = ? AND comment_id ")
                    .append(cmp).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY created_at ").append(ascending ? "ASC" : "DESC")
                .append(", comment_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), COMMENT_ROW_MAPPER, args.toArray());
    }

    // ----- timeline -----------------------------------------------------------------

    public void insertTimeline(UUID tenantId, UUID timelineId, UUID ticketId, byte[] effectKey,
                               String eventType, String summary, String relatedRefType,
                               UUID relatedRefId, String actorType, UUID actorId,
                               Instant occurredAt, UUID correlationId) {
        insertTimeline(tenantId, timelineId, ticketId, effectKey, eventType, summary,
                relatedRefType, relatedRefId, actorType, actorId, occurredAt, correlationId,
                null, occurredAt);
    }

    /** Inserts a timeline fact with explicit causation and recorded timestamp. */
    public void insertTimeline(UUID tenantId, UUID timelineId, UUID ticketId, byte[] effectKey,
                               String eventType, String summary, String relatedRefType,
                               UUID relatedRefId, String actorType, UUID actorId,
                               Instant occurredAt, UUID correlationId, UUID causationId,
                               Instant recordedAt) {
        jdbc.update("""
                INSERT INTO ticket_timeline_entry
                  (tenant_id, timeline_id, ticket_id, effect_key, event_type, summary,
                   related_ref_type, related_ref_id, actor_type, actor_id, occurred_at,
                   correlation_id, causation_id, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """, Uuids.toBytes(tenantId), Uuids.toBytes(timelineId), Uuids.toBytes(ticketId),
                 effectKey, eventType, summary, relatedRefType, Uuids.toBytes(relatedRefId),
                 actorType, Uuids.toBytes(actorId), Timestamp.from(occurredAt),
                 Uuids.toBytes(correlationId), Uuids.toBytes(causationId),
                 Timestamp.from(recordedAt == null ? occurredAt : recordedAt));
    }

    public Optional<TimelineRow> findTimelineByEffectKey(UUID tenantId, UUID ticketId, byte[] effectKey) {
        return jdbc.query("""
                SELECT timeline_id, ticket_id, effect_key, event_type, summary,
                       related_ref_type, related_ref_id, actor_type, actor_id,
                       occurred_at, correlation_id
                FROM ticket_timeline_entry
                WHERE tenant_id = ? AND ticket_id = ? AND effect_key = ?
                """, TIMELINE_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), effectKey)
                .stream().findFirst();
    }

    public List<TimelineRow> findTimelineEntries(UUID tenantId, UUID ticketId, String eventType,
                                                 Instant occurredFrom, Instant occurredTo,
                                                 boolean ascending, Object cursorValue,
                                                 UUID cursorId, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT timeline_id, ticket_id, effect_key, event_type, summary,
                       related_ref_type, related_ref_id, actor_type, actor_id,
                       occurred_at, correlation_id
                FROM ticket_timeline_entry
                WHERE tenant_id = ? AND ticket_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.add(Uuids.toBytes(ticketId));
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        if (occurredFrom != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(occurredFrom));
        }
        if (occurredTo != null) {
            sql.append(" AND occurred_at <= ?");
            args.add(Timestamp.from(occurredTo));
        }
        if (cursorValue != null && cursorId != null) {
            String cmp = ascending ? ">" : "<";
            sql.append(" AND (occurred_at ").append(cmp).append(" ? OR (occurred_at = ? AND timeline_id ")
                    .append(cmp).append(" ?))");
            args.add(cursorValue);
            args.add(cursorValue);
            args.add(Uuids.toBytes(cursorId));
        }
        sql.append(" ORDER BY occurred_at ").append(ascending ? "ASC" : "DESC")
                .append(", timeline_id ").append(ascending ? "ASC" : "DESC")
                .append(" LIMIT ").append(pageSize + 1);
        return jdbc.query(sql.toString(), TIMELINE_ROW_MAPPER, args.toArray());
    }

    // ----- audit ---------------------------------------------------------------------

    public void insertAudit(UUID tenantId, UUID auditId, String actionCode, String targetType,
                            UUID targetId, String outcome, String actorType, UUID actorId,
                            String reason, UUID correlationId, Instant occurredAt) {
        insertAudit(tenantId, auditId, actionCode, targetType, targetId, outcome, actorType,
                actorId, reason, null, correlationId, null, occurredAt, occurredAt);
    }

    /** Inserts an audit fact while preserving the incoming causation id. */
    public void insertAudit(UUID tenantId, UUID auditId, String actionCode, String targetType,
                            UUID targetId, String outcome, String actorType, UUID actorId,
                            String reason, String errorCode, UUID correlationId,
                            UUID causationId, Instant occurredAt, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO audit_record
                  (tenant_id, audit_id, action_code, target_type, target_id, outcome,
                   actor_type, actor_id, reason, error_code, correlation_id, causation_id,
                   occurred_at, recorded_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """, Uuids.toBytes(tenantId), Uuids.toBytes(auditId), actionCode, targetType,
                 Uuids.toBytes(targetId), outcome, actorType, Uuids.toBytes(actorId), reason,
                 errorCode, Uuids.toBytes(correlationId), Uuids.toBytes(causationId),
                 Timestamp.from(occurredAt), Timestamp.from(recordedAt == null ? occurredAt : recordedAt));
    }

    /**
     * Reads the immutable audit anchor without requesting an UPDATE lock. A losing duplicate
     * insert waits for the winning transaction at the unique constraint; after rollback this
     * ordinary current read sees the committed append-only row without requiring UPDATE grants.
     */
    public Optional<AuditRow> findAudit(UUID tenantId, UUID auditId) {
        return jdbc.query("""
                SELECT audit_id, action_code, target_type, target_id, outcome,
                       actor_type, actor_id, reason, error_code, correlation_id,
                       causation_id, occurred_at, recorded_at
                FROM audit_record
                WHERE tenant_id = ? AND audit_id = ?
                """, AUDIT_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(auditId))
                .stream().findFirst();
    }

    /** Returns the immutable request digest stored in the audit anchor. */
    public Optional<String> findAuditReason(UUID tenantId, UUID auditId) {
        return findAudit(tenantId, auditId).map(AuditRow::reason);
    }

    // ----- SLA clocks and triggers ---------------------------------------------------

    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds) {
        insertSlaClock(tenantId, slaClockId, ticketId, slaPolicyId, clockType, startedAt,
                deadlineAt, reminderDueAt, durationSeconds, reminderBeforeSeconds, startedAt);
    }

    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               Instant now) {
        insertSlaClock(tenantId, slaClockId, ticketId, slaPolicyId, clockType, startedAt,
                deadlineAt, reminderDueAt, durationSeconds, reminderBeforeSeconds, 0, now);
    }

    public void insertSlaClock(UUID tenantId, UUID slaClockId, UUID ticketId, UUID slaPolicyId,
                               String clockType, Instant startedAt, Instant deadlineAt,
                               Instant reminderDueAt, long durationSeconds, Long reminderBeforeSeconds,
                               int cycleNo, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (now == null || startedAt == null || deadlineAt == null
                || !deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Invalid SLA clock timestamps");
        }
        try {
            jdbc.update("""
                INSERT INTO sla_clock
                  (tenant_id, sla_clock_id, ticket_id, sla_policy_id, clock_type, status,
                   started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                   policy_reminder_before_seconds, milestone_at, breached_at, satisfied_at,
                   lock_version, created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id, cycle_no)
                 VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, NULL, NULL, NULL,
                         0, ?, 'SERVICE', NULL, ?, 'SERVICE', NULL, ?)
                 """, Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), Uuids.toBytes(ticketId),
                    Uuids.toBytes(slaPolicyId), clockType, Timestamp.from(startedAt),
                    Timestamp.from(deadlineAt), timestamp(reminderDueAt), durationSeconds,
                    reminderBeforeSeconds, Timestamp.from(now), Timestamp.from(now), cycleNo);
        }
        catch (DuplicateKeyException duplicate) {
            SlaClockRow existing = findSlaClockById(tenantId, slaClockId)
                    .orElseThrow(() -> duplicate);
            if (!slaClockId.equals(existing.slaClockId())
                    || !slaPolicyId.equals(existing.slaPolicyId())
                    || cycleNo != existing.cycleNo()
                    || !startedAt.equals(existing.startedAt())
                    || !deadlineAt.equals(existing.deadlineAt())
                    || !java.util.Objects.equals(reminderDueAt, existing.reminderDueAt())
                    || durationSeconds != existing.policyDurationSeconds()
                    || !java.util.Objects.equals(reminderBeforeSeconds,
                    existing.policyReminderBeforeSeconds())) {
                throw new IllegalStateException("SLA clock identity/content conflict", duplicate);
            }
        }
    }

    public Optional<SlaClockRow> findSlaClock(UUID tenantId, UUID ticketId, String clockType) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, sla_policy_id, clock_type, status, cycle_no,
                       started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                       policy_reminder_before_seconds, milestone_at, breached_at,
                       satisfied_at, lock_version
                FROM sla_clock
                WHERE tenant_id = ? AND ticket_id = ? AND clock_type = ?
                ORDER BY cycle_no DESC LIMIT 1
                """, SLA_CLOCK_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), clockType)
                .stream().findFirst();
    }

    public Optional<SlaClockRow> findSlaClockForUpdate(UUID tenantId, UUID ticketId,
                                                       String clockType) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, sla_policy_id, clock_type, status, cycle_no,
                       started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                       policy_reminder_before_seconds, milestone_at, breached_at,
                       satisfied_at, lock_version
                FROM sla_clock
                WHERE tenant_id = ? AND ticket_id = ? AND clock_type = ?
                ORDER BY cycle_no DESC LIMIT 1 FOR UPDATE
                """, SLA_CLOCK_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), clockType)
                .stream().findFirst();
    }

    public Optional<SlaClockRow> findSlaClockByIdForUpdate(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, sla_policy_id, clock_type, status, cycle_no,
                       started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                       policy_reminder_before_seconds, milestone_at, breached_at,
                       satisfied_at, lock_version
                FROM sla_clock
                WHERE tenant_id = ? AND sla_clock_id = ? FOR UPDATE
                """, SLA_CLOCK_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    public Optional<SlaClockRow> findSlaClockById(UUID tenantId, UUID slaClockId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, sla_policy_id, clock_type, status, cycle_no,
                       started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                       policy_reminder_before_seconds, milestone_at, breached_at,
                       satisfied_at, lock_version
                FROM sla_clock
                WHERE tenant_id = ? AND sla_clock_id = ?
                """, SLA_CLOCK_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId))
                .stream().findFirst();
    }

    /** Bounded tenant-first candidate query used by the SLA scanner and safety sweep. */
    public List<SlaCandidateRow> findSlaCandidates(UUID tenantId, Instant now,
                                                   Instant cursorUpdatedAt,
                                                   UUID cursorClockId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("SLA candidate limit must be between 1 and 200");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT sla_clock_id, ticket_id, status, clock_type, deadline_at,
                       reminder_due_at, updated_at
                FROM sla_clock
                WHERE tenant_id = ? AND status = 'RUNNING'
                  AND ((reminder_due_at IS NOT NULL AND reminder_due_at <= ?)
                       OR deadline_at <= ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        if (cursorUpdatedAt != null && cursorClockId != null) {
            sql.append(" AND (updated_at > ? OR (updated_at = ? AND sla_clock_id > ?))");
            args.add(Timestamp.from(cursorUpdatedAt));
            args.add(Timestamp.from(cursorUpdatedAt));
            args.add(Uuids.toBytes(cursorClockId));
        }
        sql.append(" ORDER BY updated_at, sla_clock_id LIMIT ").append(limit);
        return jdbc.query(sql.toString(), SLA_CANDIDATE_MAPPER, args.toArray());
    }

    /** Reads every RUNNING clock in deterministic keyset order for safety sweeps/rebuilds. */
    public List<SlaCandidateRow> findRunningSlaCandidates(UUID tenantId,
                                                           Instant cursorUpdatedAt,
                                                           UUID cursorClockId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("SLA running candidate limit must be between 1 and 500");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT sla_clock_id, ticket_id, status, clock_type, deadline_at,
                       reminder_due_at, updated_at
                FROM sla_clock
                WHERE tenant_id = ? AND status = 'RUNNING'
                """);
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (cursorUpdatedAt != null && cursorClockId != null) {
            sql.append(" AND (updated_at > ? OR (updated_at = ? AND sla_clock_id > ?))");
            args.add(Timestamp.from(cursorUpdatedAt));
            args.add(Timestamp.from(cursorUpdatedAt));
            args.add(Uuids.toBytes(cursorClockId));
        }
        sql.append(" ORDER BY updated_at, sla_clock_id LIMIT ").append(limit);
        return jdbc.query(sql.toString(), SLA_CANDIDATE_MAPPER, args.toArray());
    }

    /** Returns the greatest persisted (updated_at, id) tuple, or an empty tuple for no clocks. */
    public Optional<SlaHighWaterRow> findSlaHighWater(UUID tenantId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT updated_at, sla_clock_id
                FROM sla_clock
                WHERE tenant_id=?
                ORDER BY updated_at DESC, sla_clock_id DESC
                LIMIT 1
                """, (rs, row) -> new SlaHighWaterRow(
                instant(rs, "updated_at"), Uuids.toUuid(rs.getBytes("sla_clock_id"))),
                Uuids.toBytes(tenantId)).stream().findFirst();
    }

    /** Reads clocks changed after a rebuild high-water mark. */
    public List<SlaCandidateRow> findSlaChangesAfter(UUID tenantId, Instant updatedAt,
                                                     UUID clockId, int limit) {
        requireTenant(tenantId);
        if (updatedAt == null || clockId == null) {
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("SLA change limit must be between 1 and 500");
            }
            return jdbc.query("""
                    SELECT sla_clock_id, ticket_id, status, clock_type, deadline_at,
                           reminder_due_at, updated_at
                    FROM sla_clock
                    WHERE tenant_id=?
                    ORDER BY updated_at, sla_clock_id
                    """ + " LIMIT " + limit, SLA_CANDIDATE_MAPPER, Uuids.toBytes(tenantId));
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("SLA change limit must be between 1 and 500");
        }
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, status, clock_type, deadline_at,
                       reminder_due_at, updated_at
                FROM sla_clock
                WHERE tenant_id=?
                  AND (updated_at > ? OR (updated_at = ? AND sla_clock_id > ?))
                ORDER BY updated_at, sla_clock_id
                """ + " LIMIT " + limit, SLA_CANDIDATE_MAPPER, Uuids.toBytes(tenantId),
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), Uuids.toBytes(clockId));
    }

    /**
     * Inclusive first catch-up page for a rebuild high-water timestamp.  A new
     * clock inserted at the same microsecond as the captured high-water value
     * must not be lost merely because its UUID sorts before the captured UUID;
     * subsequent pages use the strict tuple method above.
     */
    public List<SlaCandidateRow> findSlaChangesAtOrAfter(UUID tenantId,
                                                         Instant updatedAt, int limit) {
        requireTenant(tenantId);
        if (updatedAt == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("SLA inclusive change query is invalid");
        }
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, status, clock_type, deadline_at,
                       reminder_due_at, updated_at
                FROM sla_clock
                WHERE tenant_id=? AND updated_at >= ?
                ORDER BY updated_at, sla_clock_id
                """ + " LIMIT " + limit, SLA_CANDIDATE_MAPPER,
                Uuids.toBytes(tenantId), Timestamp.from(updatedAt));
    }

    public List<UUID> findTenantIdsWithSlaClocks(int limit) {
        return findTenantIdsWithSlaClocks(null, limit);
    }

    /** Keyset variant used by long-running maintenance passes. */
    public List<UUID> findTenantIdsWithSlaClocks(UUID afterTenantId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("tenant limit must be between 1 and 1000");
        }
        if (afterTenantId == null) {
            return jdbc.query("SELECT DISTINCT tenant_id FROM sla_clock ORDER BY tenant_id LIMIT " + limit,
                    (rs, row) -> Uuids.toUuid(rs.getBytes(1)));
        }
        return jdbc.query("SELECT DISTINCT tenant_id FROM sla_clock WHERE tenant_id > ? "
                        + "ORDER BY tenant_id LIMIT " + limit,
                (rs, row) -> Uuids.toUuid(rs.getBytes(1)), Uuids.toBytes(afterTenantId));
    }

    /**
     * Returns every Ticket-owned tenant that can have SLA maintenance work.
     * Targets are included even before the first clock is created so the
     * qualification refresher can establish a usable snapshot deterministically.
     */
    public List<UUID> findTenantIdsForSlaMaintenance(int limit) {
        return findTenantIdsForSlaMaintenance(null, limit);
    }

    /** Keyset variant that prevents a bounded first page from hiding tenants. */
    public List<UUID> findTenantIdsForSlaMaintenance(UUID afterTenantId, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("tenant limit must be between 1 and 1000");
        }
        String predicate = afterTenantId == null ? "" : " WHERE tenant_id > ?";
        String sql = """
                SELECT tenant_id FROM (
                    SELECT DISTINCT tenant_id FROM sla_clock
                    UNION
                    SELECT DISTINCT tenant_id FROM sla_escalation_target
                ) tenants
                """ + predicate + " ORDER BY tenant_id LIMIT " + limit;
        return afterTenantId == null
                ? jdbc.query(sql, (rs, row) -> Uuids.toUuid(rs.getBytes(1)))
                : jdbc.query(sql, (rs, row) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(afterTenantId));
    }

    public List<SlaClockRow> findSlaClocks(UUID tenantId, UUID ticketId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT sla_clock_id, ticket_id, sla_policy_id, clock_type, status, cycle_no,
                       started_at, deadline_at, reminder_due_at, policy_duration_seconds,
                       policy_reminder_before_seconds, milestone_at, breached_at,
                       satisfied_at, lock_version
                FROM sla_clock
                WHERE tenant_id = ? AND ticket_id = ?
                ORDER BY cycle_no DESC, clock_type, sla_clock_id
                """, SLA_CLOCK_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId));
    }

    public List<SlaTriggerRow> findSlaTriggers(UUID tenantId, Collection<UUID> slaClockIds) {
        requireTenant(tenantId);
        if (slaClockIds.isEmpty()) {
            return List.of();
        }
        List<byte[]> ids = slaClockIds.stream().map(Uuids::toBytes).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        args.addAll(ids);
        return jdbc.query("""
                SELECT sla_clock_id, stage, due_at, triggered_at
                FROM sla_trigger
                WHERE tenant_id = ? AND sla_clock_id IN (""" + placeholders + """
                )
                ORDER BY due_at, sla_clock_id
                """, SLA_TRIGGER_ROW_MAPPER, args.toArray());
    }

    public int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             Instant now) {
        return updateSlaClockSatisfiedOnTime(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    /** Updates a clock with the actor that accepted the milestone. */
    public int updateSlaClockSatisfiedOnTime(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                             String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return jdbc.update("""
                UPDATE sla_clock
                SET status = 'SATISFIED_ON_TIME',
                    milestone_at = ?,
                    satisfied_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = CASE WHEN updated_at >= ?
                        THEN TIMESTAMPADD(MICROSECOND, 1, updated_at) ELSE ? END,
                    updated_by_type = ?,
                    updated_by_id = ?
                WHERE tenant_id = ? AND sla_clock_id = ?
                  AND status = 'RUNNING' AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    public int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      Instant now) {
        return updateSlaClockBreached(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    public int updateSlaClockBreached(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                      String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return jdbc.update("""
                UPDATE sla_clock
                SET status = 'BREACHED',
                    breached_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = CASE WHEN updated_at >= ?
                        THEN TIMESTAMPADD(MICROSECOND, 1, updated_at) ELSE ? END,
                    updated_by_type = ?,
                    updated_by_id = ?
                WHERE tenant_id = ? AND sla_clock_id = ?
                  AND status = 'RUNNING' AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    public int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           Instant now) {
        return updateSlaClockSatisfiedLate(tenantId, slaClockId, expectedLockVersion,
                "SCHEDULER", null, now);
    }

    public int updateSlaClockSatisfiedLate(UUID tenantId, UUID slaClockId, long expectedLockVersion,
                                           String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        validateSlaActor(actorType, actorId);
        return jdbc.update("""
                UPDATE sla_clock
                SET status = 'SATISFIED_LATE',
                    milestone_at = ?,
                    satisfied_at = ?,
                    lock_version = lock_version + 1,
                    updated_at = CASE WHEN updated_at >= ?
                        THEN TIMESTAMPADD(MICROSECOND, 1, updated_at) ELSE ? END,
                    updated_by_type = ?,
                    updated_by_id = ?
                WHERE tenant_id = ? AND sla_clock_id = ?
                  AND status = 'BREACHED' AND lock_version = ?
                """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                actorType, Uuids.toBytes(actorId), Uuids.toBytes(tenantId),
                Uuids.toBytes(slaClockId), expectedLockVersion);
    }

    public boolean hasNotificationPublication(UUID tenantId, byte[] publicationKey, UUID sourceRefId) {
        requireTenant(tenantId);
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND publication_key=? AND aggregate_type='NOTIFICATION_REQUEST' AND aggregate_id=?",
                Long.class, Uuids.toBytes(tenantId), publicationKey, Uuids.toBytes(sourceRefId)) > 0;
    }

    public List<PendingSlaNotification> findPendingSlaNotifications(UUID tenantId, UUID afterTriggerId, int limit) {
        requireTenant(tenantId);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid SLA retry batch size");
        String sql = """
                SELECT t.sla_trigger_id,t.sla_clock_id,t.stage,c.ticket_id
                FROM sla_trigger t JOIN sla_clock c
                  ON c.tenant_id=t.tenant_id AND c.sla_clock_id=t.sla_clock_id
                WHERE t.tenant_id=? AND NOT EXISTS (
                  SELECT 1 FROM sla_trigger_recipient r
                  WHERE r.tenant_id=t.tenant_id AND r.sla_trigger_id=t.sla_trigger_id)
                """;
        List<Object> args = new ArrayList<>();
        args.add(Uuids.toBytes(tenantId));
        if (afterTriggerId != null) { sql += " AND t.sla_trigger_id > ?"; args.add(Uuids.toBytes(afterTriggerId)); }
        sql += " ORDER BY t.sla_trigger_id LIMIT " + limit;
        return jdbc.query(sql, (rs, n) -> new PendingSlaNotification(
                Uuids.toUuid(rs.getBytes("sla_trigger_id")), Uuids.toUuid(rs.getBytes("sla_clock_id")),
                Uuids.toUuid(rs.getBytes("ticket_id")), rs.getString("stage")), args.toArray());
    }

    public record PendingSlaNotification(UUID triggerId, UUID clockId, UUID ticketId, String stage) { }

    public Optional<SlaTriggerDetailRow> findSlaTrigger(UUID tenantId, UUID slaClockId, String stage,
                                                        boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return jdbc.query("""
                SELECT sla_clock_id, stage, effect_key, due_at, triggered_at, publication_key
                FROM sla_trigger
                WHERE tenant_id = ? AND sla_clock_id = ? AND stage = ?
                """ + (lock ? " FOR UPDATE" : ""), SLA_TRIGGER_DETAIL_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(slaClockId), stage).stream().findFirst();
    }

    /** Inserts one immutable reminder/breach trigger; the unique key absorbs races. */
    public void insertSlaTrigger(UUID tenantId, UUID triggerId, UUID slaClockId, String stage,
                                 byte[] effectKey, Instant dueAt, Instant triggeredAt,
                                 byte[] publicationKey) {
        requireTransaction();
        try {
            jdbc.update("""
                INSERT INTO sla_trigger
                  (tenant_id, sla_trigger_id, sla_clock_id, stage, effect_key, due_at,
                   triggered_at, publication_key, created_at, created_by_type, created_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULER', NULL)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(triggerId), Uuids.toBytes(slaClockId),
                    stage, effectKey, Timestamp.from(dueAt), Timestamp.from(triggeredAt), publicationKey,
                    Timestamp.from(triggeredAt));
        }
        catch (DuplicateKeyException duplicate) {
            SlaTriggerDetailRow existing = findSlaTrigger(tenantId, slaClockId, stage, false)
                    .orElseThrow(() -> duplicate);
            if (!triggerId.equals(uuidFromKey(existing.effectKey()))
                    || !java.util.Arrays.equals(effectKey, existing.effectKey())
                    || !dueAt.equals(existing.dueAt())
                    || !java.util.Arrays.equals(publicationKey, existing.publicationKey())) {
                throw new IllegalStateException("SLA trigger identity/content conflict", duplicate);
            }
        }
    }

    /**
     * Persists the concrete recipient set selected at trigger creation.  The
     * set is immutable: a replay with the same trigger may only observe the
     * exact existing members, never replace them with the current assignee or
     * target mapping.
     */
    public void insertSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId,
                                                  Collection<UUID> recipients,
                                                  String createdByType, UUID createdById,
                                                  Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (triggerId == null || recipients == null || recipients.isEmpty()
                || createdByType == null || now == null) {
            throw new IllegalArgumentException("SLA trigger recipient snapshot is incomplete");
        }
        java.util.LinkedHashSet<UUID> expected = new java.util.LinkedHashSet<>();
        for (UUID recipient : recipients) {
            if (recipient == null || (recipient.getMostSignificantBits() == 0L
                    && recipient.getLeastSignificantBits() == 0L)) {
                throw new IllegalArgumentException("SLA trigger recipient must be concrete");
            }
            expected.add(recipient);
        }
        List<UUID> existing = findSlaTriggerRecipientSnapshot(tenantId, triggerId);
        if (!existing.isEmpty()) {
            if (!new java.util.LinkedHashSet<>(existing).equals(expected)) {
                throw new IllegalStateException("SLA trigger recipient snapshot content conflict");
            }
            return;
        }
        for (UUID recipient : expected) {
            try {
                jdbc.update("""
                    INSERT INTO sla_trigger_recipient
                      (tenant_id,sla_trigger_id,recipient_user_id,created_at,
                       created_by_type,created_by_id)
                    VALUES (?,?,?,?,?,?)
                    """, Uuids.toBytes(tenantId), Uuids.toBytes(triggerId),
                        Uuids.toBytes(recipient), Timestamp.from(now), createdByType,
                        Uuids.toBytes(createdById));
            }
            catch (DuplicateKeyException duplicate) {
                // A concurrent scanner may have inserted the same immutable
                // member.  Re-read the complete set and validate below.
            }
        }
        List<UUID> actual = findSlaTriggerRecipientSnapshot(tenantId, triggerId);
        if (!new java.util.LinkedHashSet<>(actual).equals(expected)) {
            throw new IllegalStateException("SLA trigger recipient snapshot write was incomplete");
        }
    }

    public List<UUID> findSlaTriggerRecipientSnapshot(UUID tenantId, UUID triggerId) {
        requireTenant(tenantId);
        // This table is an immutable child set and the runtime principal is intentionally
        // SELECT/INSERT-only. The caller already serializes writers by locking the parent
        // Ticket and SLA clock, so a child SELECT ... FOR UPDATE is both redundant and
        // incompatible with the least-privilege contract on real MySQL.
        return jdbc.query("""
                SELECT recipient_user_id
                FROM sla_trigger_recipient
                WHERE tenant_id=? AND sla_trigger_id=?
                ORDER BY recipient_user_id
                """,
                (rs, row) -> Uuids.toUuid(rs.getBytes(1)),
                Uuids.toBytes(tenantId), Uuids.toBytes(triggerId));
    }

    public int updateSlaClockUpdatedAt(UUID tenantId, UUID slaClockId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (now == null) throw new IllegalArgumentException("SLA clock update time is required");
        requireTransaction();
        return jdbc.update("""
                UPDATE sla_clock
                SET updated_at = CASE WHEN updated_at >= ?
                    THEN TIMESTAMPADD(MICROSECOND, 1, updated_at) ELSE ? END,
                    updated_by_type='SCHEDULER',updated_by_id=NULL
                WHERE tenant_id=? AND sla_clock_id=?
                """, Timestamp.from(now), Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(slaClockId));
    }

    // ----- concrete SLA escalation target / qualification -------------------------

    public Optional<SlaEscalationTargetRow> findActiveSlaEscalationTarget(UUID tenantId,
                                                                           boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return jdbc.query("""
                SELECT target_id, recipient_user_id, purpose, status, effective_at,
                       qualification_version, qualification_checked_at,
                       qualification_valid_until, qualification_state,
                       qualification_reason_code, lock_version, created_at, updated_at
                FROM sla_escalation_target
                WHERE tenant_id=? AND purpose='SLA' AND status='ACTIVE'
                """ + (lock ? " FOR UPDATE" : ""), SLA_TARGET_MAPPER,
                Uuids.toBytes(tenantId)).stream().findFirst();
    }

    /** Reads a target by its stable mapping identity, including inactive history. */
    public Optional<SlaEscalationTargetRow> findSlaEscalationTarget(UUID tenantId,
                                                                     UUID targetId,
                                                                     boolean lock) {
        requireTenant(tenantId);
        if (targetId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT target_id, recipient_user_id, purpose, status, effective_at,
                       qualification_version, qualification_checked_at,
                       qualification_valid_until, qualification_state,
                       qualification_reason_code, lock_version, created_at, updated_at
                FROM sla_escalation_target
                WHERE tenant_id=? AND target_id=?
                """ + (lock ? " FOR UPDATE" : ""), SLA_TARGET_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(targetId)).stream().findFirst();
    }

    /**
     * Inserts one Ticket-owned concrete target.  The generated active guard and
     * unique key enforce the one ACTIVE SLA mapping per tenant in the database.
     */
    public int insertSlaEscalationTarget(UUID tenantId, UUID targetId,
                                         UUID recipientUserId, String purpose,
                                         String status, Instant effectiveAt,
                                         Long qualificationVersion,
                                         Instant qualificationCheckedAt,
                                         Instant qualificationValidUntil,
                                         String qualificationState,
                                         String qualificationReasonCode,
                                         String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (targetId == null || recipientUserId == null || effectiveAt == null
                || purpose == null || status == null || qualificationState == null
                || actorType == null || now == null) {
            throw new IllegalArgumentException("SLA target insert values are incomplete");
        }
        return jdbc.update("""
                INSERT INTO sla_escalation_target
                  (tenant_id,target_id,purpose,recipient_user_id,status,effective_at,
                   qualification_version,qualification_checked_at,qualification_valid_until,
                   qualification_state,qualification_reason_code,lock_version,
                   created_at,created_by_type,created_by_id,updated_at,updated_by_type,updated_by_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(targetId), purpose,
                Uuids.toBytes(recipientUserId), status, Timestamp.from(effectiveAt),
                qualificationVersion, timestamp(qualificationCheckedAt),
                timestamp(qualificationValidUntil), qualificationState,
                qualificationReasonCode, Timestamp.from(now), actorType, Uuids.toBytes(actorId),
                Timestamp.from(now), actorType, Uuids.toBytes(actorId));
    }

    /** Deactivates a mapping without deleting its historical row. */
    public int deactivateSlaEscalationTarget(UUID tenantId, UUID targetId,
                                             long expectedLockVersion,
                                             String actorType, UUID actorId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE sla_escalation_target
                SET status='INACTIVE',lock_version=lock_version+1,updated_at=?,
                    updated_by_type=?,updated_by_id=?
                WHERE tenant_id=? AND target_id=? AND status='ACTIVE' AND lock_version=?
                """, Timestamp.from(now), actorType, Uuids.toBytes(actorId),
                Uuids.toBytes(tenantId), Uuids.toBytes(targetId), expectedLockVersion);
    }

    public int countActiveSlaEscalationTargets(UUID tenantId) {
        requireTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sla_escalation_target
                WHERE tenant_id=? AND purpose='SLA' AND status='ACTIVE'
                """, Integer.class, Uuids.toBytes(tenantId));
        return count == null ? 0 : count;
    }

    public int upsertQualificationSnapshot(UUID tenantId, UUID targetId, UUID recipientUserId,
                                           long qualificationVersion, Instant checkedAt,
                                           Instant validUntil, boolean eligible,
                                           String reasonCode, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                INSERT INTO sla_qualification_snapshot
                  (tenant_id,target_id,recipient_user_id,purpose,qualification_version,
                   checked_at,valid_until,eligible,reason_code,created_at,created_by_type,created_by_id)
                VALUES (?,?,?,'SLA',?,?,?,?,?,?, 'SERVICE', NULL)
                ON DUPLICATE KEY UPDATE recipient_user_id=VALUES(recipient_user_id),
                   qualification_version=VALUES(qualification_version),
                   checked_at=VALUES(checked_at),valid_until=VALUES(valid_until),
                   eligible=VALUES(eligible),reason_code=VALUES(reason_code)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(targetId), Uuids.toBytes(recipientUserId),
                qualificationVersion, Timestamp.from(checkedAt), Timestamp.from(validUntil),
                eligible, reasonCode, Timestamp.from(now));
    }

    public Optional<SlaQualificationRow> findQualificationSnapshot(UUID tenantId, UUID targetId) {
        return findQualificationSnapshot(tenantId, targetId, false);
    }

    public Optional<SlaQualificationRow> findQualificationSnapshot(UUID tenantId, UUID targetId,
                                                                    boolean lock) {
        requireTenant(tenantId);
        if (lock) requireTransaction();
        return jdbc.query("""
                SELECT target_id,recipient_user_id,purpose,qualification_version,checked_at,valid_until,
                       eligible,reason_code
                FROM sla_qualification_snapshot
                WHERE tenant_id=? AND target_id=?
                """ + (lock ? " FOR UPDATE" : ""), SLA_QUALIFICATION_MAPPER,
                Uuids.toBytes(tenantId), Uuids.toBytes(targetId))
                .stream().findFirst();
    }

    public int updateSlaTargetQualification(UUID tenantId, UUID targetId, long expectedVersion,
                                            long qualificationVersion, Instant checkedAt,
                                            Instant validUntil, boolean eligible, String reason,
                                            String state, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.update("""
                UPDATE sla_escalation_target
                SET qualification_version=?,qualification_checked_at=?,qualification_valid_until=?,
                    qualification_state=?,qualification_reason_code=?,lock_version=lock_version+1,
                    updated_at=?,updated_by_type='SERVICE',updated_by_id=NULL
                WHERE tenant_id=? AND target_id=? AND purpose='SLA' AND status='ACTIVE'
                  AND lock_version=?
                """, qualificationVersion, Timestamp.from(checkedAt), Timestamp.from(validUntil),
                state, reason, Timestamp.from(now), Uuids.toBytes(tenantId), Uuids.toBytes(targetId),
                expectedVersion);
    }

    // ----- Redis rebuild lease ------------------------------------------------------

    public Optional<SlaRebuildLeaseRow> findSlaRebuildLeaseForUpdate(UUID tenantId) {
        requireTenant(tenantId);
        requireTransaction();
        return jdbc.query("""
                SELECT tenant_id,fencing_epoch,run_id,high_water_updated_at,
                       high_water_sla_clock_id,expires_at,state,last_error_code,
                       lock_version,created_at,updated_at
                FROM sla_index_rebuild_lease WHERE tenant_id=? FOR UPDATE
                """, SLA_REBUILD_LEASE_MAPPER, Uuids.toBytes(tenantId)).stream().findFirst();
    }

    public Optional<SlaRebuildLeaseRow> findSlaRebuildLease(UUID tenantId) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id,fencing_epoch,run_id,high_water_updated_at,
                       high_water_sla_clock_id,expires_at,state,last_error_code,
                       lock_version,created_at,updated_at
                FROM sla_index_rebuild_lease WHERE tenant_id=?
                """, SLA_REBUILD_LEASE_MAPPER, Uuids.toBytes(tenantId)).stream().findFirst();
    }

    public void ensureSlaRebuildLease(UUID tenantId, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        jdbc.update("""
                INSERT INTO sla_index_rebuild_lease
                  (tenant_id,fencing_epoch,state,created_at,updated_at,updated_by_type)
                VALUES (?,0,'IDLE',?,?,'SCHEDULER')
                ON DUPLICATE KEY UPDATE updated_at=updated_at
                """, Uuids.toBytes(tenantId), Timestamp.from(now), Timestamp.from(now));
    }

    public int claimSlaRebuildLease(UUID tenantId, UUID runId, Instant highWaterAt,
                                    UUID highWaterClockId, Instant expiresAt, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (tenantId == null || runId == null || expiresAt == null || now == null
                || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease claim");
        }
        return jdbc.update("""
                UPDATE sla_index_rebuild_lease
                SET fencing_epoch=fencing_epoch+1,run_id=?,high_water_updated_at=?,
                    high_water_sla_clock_id=?,expires_at=?,state='RUNNING',last_error_code=NULL,
                    lock_version=lock_version+1,updated_at=?,updated_by_type='SCHEDULER'
                WHERE tenant_id=?
                  AND (
                      state='IDLE'
                      OR (state='DEGRADED' AND run_id IS NULL)
                      OR (state IN ('RUNNING','CATCHING_UP','SWAPPING','DEGRADED')
                          AND run_id IS NOT NULL AND expires_at IS NOT NULL AND expires_at < ?)
                  )
                """, Uuids.toBytes(runId), timestamp(highWaterAt), Uuids.toBytes(highWaterClockId),
                Timestamp.from(expiresAt), Timestamp.from(now), Uuids.toBytes(tenantId),
                Timestamp.from(now));
    }

    /** Renews a lease only for the exact run and fencing epoch while it is live. */
    public int renewSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                    Instant expiresAt, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (runId == null || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease renewal");
        }
        return jdbc.update("""
                UPDATE sla_index_rebuild_lease
                SET expires_at=?,updated_at=?,updated_by_type='SCHEDULER',
                    lock_version=lock_version+1
                WHERE tenant_id=? AND run_id=? AND fencing_epoch=?
                  AND state IN ('RUNNING','CATCHING_UP','SWAPPING')
                  AND expires_at IS NOT NULL AND expires_at > ?
                """, Timestamp.from(expiresAt), Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(runId), fencingEpoch, Timestamp.from(now));
    }

    public boolean isSlaRebuildActive(UUID tenantId, Instant now) {
        requireTenant(tenantId);
        return jdbc.query("""
                SELECT 1 FROM sla_index_rebuild_lease
                WHERE tenant_id=? AND state IN ('RUNNING','CATCHING_UP','SWAPPING')
                  AND run_id IS NOT NULL AND expires_at IS NOT NULL AND expires_at > ?
                LIMIT 1
                """, (rs, row) -> Boolean.TRUE, Uuids.toBytes(tenantId), Timestamp.from(now))
                .stream().findFirst().orElse(Boolean.FALSE);
    }

    /** Fenced state transition; an expired or replaced owner cannot advance. */
    public int advanceSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, Instant expiresAt, String errorCode,
                                      Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (!java.util.Set.of("RUNNING", "CATCHING_UP", "SWAPPING", "DEGRADED")
                .contains(state) || expiresAt == null || now == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease state transition");
        }
        return jdbc.update("""
                UPDATE sla_index_rebuild_lease SET state=?,expires_at=?,last_error_code=?,
                    updated_at=?,updated_by_type='SCHEDULER',lock_version=lock_version+1
                WHERE tenant_id=? AND run_id=? AND fencing_epoch=? AND state <> 'IDLE'
                  AND expires_at IS NOT NULL AND expires_at > ?
                """, state, Timestamp.from(expiresAt), errorCode, Timestamp.from(now),
                Uuids.toBytes(tenantId), Uuids.toBytes(runId), fencingEpoch, Timestamp.from(now));
    }

    public int advanceSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      Instant expiresAt, String errorCode, Instant now) {
        SlaRebuildLeaseRow owner = findSlaRebuildLease(tenantId).orElse(null);
        if (owner == null || !runId.equals(owner.runId())) return 0;
        return advanceSlaRebuildLease(tenantId, runId, owner.fencingEpoch(), state,
                expiresAt, errorCode, now);
    }

    public int releaseSlaRebuildLease(UUID tenantId, UUID runId, long fencingEpoch,
                                      String state, String errorCode, Instant now) {
        requireTenant(tenantId);
        requireTransaction();
        if (!java.util.Set.of("IDLE", "DEGRADED").contains(state) || now == null) {
            throw new IllegalArgumentException("Invalid SLA rebuild lease release state");
        }
        return jdbc.update("""
                UPDATE sla_index_rebuild_lease SET state=?,run_id=NULL,expires_at=NULL,
                    last_error_code=?,lock_version=lock_version+1,updated_at=?,updated_by_type='SCHEDULER'
                WHERE tenant_id=? AND run_id=? AND fencing_epoch=?
                """, state, errorCode, Timestamp.from(now), Uuids.toBytes(tenantId),
                Uuids.toBytes(runId), fencingEpoch);
    }

    public int releaseSlaRebuildLease(UUID tenantId, UUID runId, String state,
                                      String errorCode, Instant now) {
        SlaRebuildLeaseRow owner = findSlaRebuildLease(tenantId).orElse(null);
        if (owner == null || !runId.equals(owner.runId())) return 0;
        return releaseSlaRebuildLease(tenantId, runId, owner.fencingEpoch(), state,
                errorCode, now);
    }

    // ----- high-risk workflow start / outbox -------------------------------------------

    public Optional<WorkflowStartRow> findWorkflowStartLink(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT ticket_id, status, workflow_instance_id, requested_at, established_at,
                       recovery_required_at, recovery_reason_code, retry_count, last_attempt_at,
                       lock_version, created_at, updated_at
                FROM workflow_start_link
                WHERE tenant_id = ? AND ticket_id = ?
                """, WORKFLOW_START_ROW_MAPPER, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId))
                .stream().findFirst();
    }

    public void insertWorkflowStartLink(UUID tenantId, UUID ticketId, byte[] startIntentKey,
                                        Instant requestedAt, UUID correlationId) {
        jdbc.update("""
                INSERT INTO workflow_start_link
                  (tenant_id, ticket_id, start_intent_key, status, workflow_instance_id,
                   requested_at, established_at, recovery_required_at, recovery_reason_code,
                   retry_count, last_attempt_at, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, 'REQUESTED', NULL, ?, NULL, NULL, NULL,
                        0, NULL, 0, CURRENT_TIMESTAMP(6), 'SERVICE', NULL,
                        CURRENT_TIMESTAMP(6), 'SERVICE', NULL)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(ticketId), startIntentKey,
                Timestamp.from(requestedAt));
    }

    public void insertOutboxEvent(UUID tenantId, UUID outboxId, UUID messageId,
                                  byte[] publicationKey, byte[] businessIntentKey,
                                  String aggregateType, UUID aggregateId, String contractRef,
                                  int contractVersion, byte[] bodyBytes, byte[] bodySha256,
                                  UUID correlationId,
                                  Instant createdAt) {
        insertOutboxEvent(tenantId, outboxId, messageId, publicationKey, businessIntentKey,
                aggregateType, aggregateId, contractRef, contractVersion, bodyBytes, bodySha256,
                correlationId, null, createdAt);
    }

    public void insertOutboxEvent(UUID tenantId, UUID outboxId, UUID messageId,
                                  byte[] publicationKey, byte[] businessIntentKey,
                                  String aggregateType, UUID aggregateId, String contractRef,
                                  int contractVersion, byte[] bodyBytes, byte[] bodySha256,
                                  UUID correlationId, UUID causationId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO outbox_event
                  (tenant_id, outbox_id, message_id, publication_key, business_intent_key,
                   aggregate_type, aggregate_id, contract_ref, contract_version, body_bytes,
                   body_sha256, correlation_id, causation_id, status, attempt_count,
                   next_attempt_at, locked_by, locked_at, last_error_code,
                   broker_confirmed_at, lock_version,
                   created_at, created_by_type, created_by_id,
                   updated_at, updated_by_type, updated_by_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0,
                        ?, NULL, NULL, NULL, NULL, 0,
                        ?, 'SERVICE', NULL, ?, 'SERVICE', NULL)
                """, Uuids.toBytes(tenantId), Uuids.toBytes(outboxId), Uuids.toBytes(messageId),
                publicationKey, businessIntentKey, aggregateType, Uuids.toBytes(aggregateId),
                contractRef, contractVersion, bodyBytes, bodySha256, Uuids.toBytes(correlationId),
                Uuids.toBytes(causationId), Timestamp.from(createdAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    // ----- row mappers -------------------------------------------------------------------

    private static final org.springframework.jdbc.core.RowMapper<CatalogItemRow> CATALOG_ROW_MAPPER =
            (rs, i) -> new CatalogItemRow(
                    Uuids.toUuid(rs.getBytes("catalog_item_id")),
                    rs.getString("item_code"),
                    rs.getString("item_name"),
                    rs.getString("description"),
                    rs.getString("risk_level"),
                    rs.getBoolean("requires_approval"),
                    Uuids.toUuid(rs.getBytes("workflow_definition_id")),
                    Uuids.toUuid(rs.getBytes("workflow_version_id")),
                    nullableInt(rs, "workflow_version_no"),
                    Uuids.toUuid(rs.getBytes("sla_policy_id")),
                    rs.getString("status"),
                    rs.getLong("lock_version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final org.springframework.jdbc.core.RowMapper<SlaPolicyRow> SLA_POLICY_MAPPER =
            (rs, i) -> new SlaPolicyRow(
                    Uuids.toUuid(rs.getBytes("sla_policy_id")),
                    rs.getString("policy_code"),
                    rs.getString("policy_name"),
                    rs.getLong("first_response_seconds"),
                    rs.getLong("resolution_seconds"),
                    nullableLong(rs, "first_response_reminder_before_seconds"),
                    nullableLong(rs, "resolution_reminder_before_seconds"),
                    rs.getString("status"),
                    rs.getLong("lock_version"));

    private static final org.springframework.jdbc.core.RowMapper<TicketRow> TICKET_ROW_MAPPER =
            (rs, i) -> new TicketRow(
                    Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getString("ticket_no"),
                    rs.getBytes("creation_intent_key"),
                    rs.getString("origin_type"),
                    Uuids.toUuid(rs.getBytes("catalog_item_id")),
                    Uuids.toUuid(rs.getBytes("requester_user_id")),
                    Uuids.toUuid(rs.getBytes("previous_attempt_ticket_id")),
                    rs.getString("subject"),
                    rs.getString("description"),
                    rs.getString("status"),
                    Uuids.toUuid(rs.getBytes("assignee_user_id")),
                    Uuids.toUuid(rs.getBytes("workflow_definition_id")),
                    Uuids.toUuid(rs.getBytes("workflow_version_id")),
                    nullableInt(rs, "workflow_version_no"),
                    instant(rs, "first_processable_at"),
                    instant(rs, "resolved_at"),
                    instant(rs, "closed_at"),
                    instant(rs, "last_reopened_at"),
                    rs.getInt("reopen_count"),
                    rs.getLong("lock_version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final org.springframework.jdbc.core.RowMapper<CommentRow> COMMENT_ROW_MAPPER =
            (rs, i) -> new CommentRow(
                    Uuids.toUuid(rs.getBytes("comment_id")),
                    Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getString("body"),
                    Uuids.toUuid(rs.getBytes("created_by_id")),
                    instant(rs, "created_at"),
                    Uuids.toUuid(rs.getBytes("correlation_id")));

    private static final org.springframework.jdbc.core.RowMapper<TimelineRow> TIMELINE_ROW_MAPPER =
            (rs, i) -> new TimelineRow(
                    Uuids.toUuid(rs.getBytes("timeline_id")),
                    Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getBytes("effect_key"),
                    rs.getString("event_type"),
                    rs.getString("summary"),
                    rs.getString("related_ref_type"),
                    Uuids.toUuid(rs.getBytes("related_ref_id")),
                    rs.getString("actor_type"),
                    Uuids.toUuid(rs.getBytes("actor_id")),
                    instant(rs, "occurred_at"),
                    Uuids.toUuid(rs.getBytes("correlation_id")));

    private static final org.springframework.jdbc.core.RowMapper<AuditRow> AUDIT_ROW_MAPPER =
            (rs, i) -> new AuditRow(
                    Uuids.toUuid(rs.getBytes("audit_id")),
                    rs.getString("action_code"),
                    rs.getString("target_type"),
                    Uuids.toUuid(rs.getBytes("target_id")),
                    rs.getString("outcome"),
                    rs.getString("actor_type"),
                    Uuids.toUuid(rs.getBytes("actor_id")),
                    rs.getString("reason"),
                    rs.getString("error_code"),
                    Uuids.toUuid(rs.getBytes("correlation_id")),
                    Uuids.toUuid(rs.getBytes("causation_id")),
                    instant(rs, "occurred_at"),
                    instant(rs, "recorded_at"));

    private static final org.springframework.jdbc.core.RowMapper<SlaClockRow> SLA_CLOCK_ROW_MAPPER =
            (rs, i) -> new SlaClockRow(
                    Uuids.toUuid(rs.getBytes("sla_clock_id")),
                    Uuids.toUuid(rs.getBytes("ticket_id")),
                    Uuids.toUuid(rs.getBytes("sla_policy_id")),
                    rs.getString("clock_type"),
                    rs.getString("status"),
                    instant(rs, "started_at"),
                    instant(rs, "deadline_at"),
                    instant(rs, "reminder_due_at"),
                    rs.getLong("policy_duration_seconds"),
                    nullableLong(rs, "policy_reminder_before_seconds"),
                    instant(rs, "milestone_at"),
                    instant(rs, "breached_at"),
                    instant(rs, "satisfied_at"),
                    rs.getLong("lock_version"), rs.getInt("cycle_no"));

    private static final org.springframework.jdbc.core.RowMapper<SlaTriggerRow> SLA_TRIGGER_ROW_MAPPER =
            (rs, i) -> new SlaTriggerRow(
                    Uuids.toUuid(rs.getBytes("sla_clock_id")),
                    rs.getString("stage"),
                    instant(rs, "due_at"),
                    instant(rs, "triggered_at"));

    private static final org.springframework.jdbc.core.RowMapper<SlaTriggerDetailRow>
            SLA_TRIGGER_DETAIL_MAPPER = (rs, i) -> new SlaTriggerDetailRow(
                    Uuids.toUuid(rs.getBytes("sla_clock_id")), rs.getString("stage"),
                    rs.getBytes("effect_key"), instant(rs, "due_at"),
                    instant(rs, "triggered_at"), rs.getBytes("publication_key"));

    private static final org.springframework.jdbc.core.RowMapper<SlaCandidateRow>
            SLA_CANDIDATE_MAPPER = (rs, i) -> new SlaCandidateRow(
                    Uuids.toUuid(rs.getBytes("sla_clock_id")),
                    Uuids.toUuid(rs.getBytes("ticket_id")), rs.getString("status"),
                    rs.getString("clock_type"), instant(rs, "deadline_at"),
                    instant(rs, "reminder_due_at"), instant(rs, "updated_at"));

    private static final org.springframework.jdbc.core.RowMapper<SlaEscalationTargetRow>
            SLA_TARGET_MAPPER = (rs, i) -> new SlaEscalationTargetRow(
                    Uuids.toUuid(rs.getBytes("target_id")),
                    Uuids.toUuid(rs.getBytes("recipient_user_id")), rs.getString("purpose"),
                    rs.getString("status"), instant(rs, "effective_at"),
                    nullableLong(rs, "qualification_version"),
                    instant(rs, "qualification_checked_at"),
                    instant(rs, "qualification_valid_until"), rs.getString("qualification_state"),
                    rs.getString("qualification_reason_code"), rs.getLong("lock_version"),
                    instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final org.springframework.jdbc.core.RowMapper<SlaQualificationRow>
            SLA_QUALIFICATION_MAPPER = (rs, i) -> new SlaQualificationRow(
                    Uuids.toUuid(rs.getBytes("target_id")),
                    Uuids.toUuid(rs.getBytes("recipient_user_id")),
                    rs.getString("purpose"),
                    rs.getLong("qualification_version"), instant(rs, "checked_at"),
                    instant(rs, "valid_until"), rs.getBoolean("eligible"),
                    rs.getString("reason_code"));

    private static final org.springframework.jdbc.core.RowMapper<SlaRebuildLeaseRow>
            SLA_REBUILD_LEASE_MAPPER = (rs, i) -> new SlaRebuildLeaseRow(
                    Uuids.toUuid(rs.getBytes("tenant_id")), rs.getLong("fencing_epoch"),
                    Uuids.toUuid(rs.getBytes("run_id")), instant(rs, "high_water_updated_at"),
                    Uuids.toUuid(rs.getBytes("high_water_sla_clock_id")), instant(rs, "expires_at"),
                    rs.getString("state"), rs.getString("last_error_code"),
                    rs.getLong("lock_version"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final org.springframework.jdbc.core.RowMapper<WorkflowStartRow> WORKFLOW_START_ROW_MAPPER =
            (rs, i) -> new WorkflowStartRow(
                    Uuids.toUuid(rs.getBytes("ticket_id")),
                    rs.getString("status"),
                    Uuids.toUuid(rs.getBytes("workflow_instance_id")),
                    instant(rs, "requested_at"),
                    instant(rs, "established_at"),
                    instant(rs, "recovery_required_at"),
                    rs.getString("recovery_reason_code"),
                    rs.getInt("retry_count"),
                    instant(rs, "last_attempt_at"),
                    rs.getLong("lock_version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    // ----- helpers -----------------------------------------------------------------------

    /** Every tenant-owned SLA statement must execute under the matching context. */
    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        TenantContextHolder.current().requireTenant(tenantId);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ticket mutation requires an active transaction");
        }
    }

    private static void validateSlaActor(String actorType, UUID actorId) {
        if (!("USER".equals(actorType) || "SCHEDULER".equals(actorType)
                || "SERVICE".equals(actorType))) {
            throw new IllegalArgumentException("Unsupported SLA clock actor type");
        }
        if ("USER".equals(actorType) && actorId == null) {
            throw new IllegalArgumentException("User SLA clock updates require actorId");
        }
        if (!"USER".equals(actorType) && actorId != null) {
            throw new IllegalArgumentException("Non-user SLA clock updates cannot carry actorId");
        }
    }

    private static String catalogSortColumn(String sort) {
        return switch (sort) {
            case "itemCode" -> "item_code";
            case "itemName" -> "item_name";
            default -> throw new IllegalArgumentException("Unsupported catalog sort: " + sort);
        };
    }

    private static String slaSortColumn(String sort) {
        return switch (sort) {
            case "policyCode" -> "policy_code";
            case "policyName" -> "policy_name";
            default -> throw new IllegalArgumentException("Unsupported SLA sort: " + sort);
        };
    }

    private static String ticketSortColumn(String sort) {
        return switch (sort) {
            case "updatedAt" -> "t.updated_at";
            case "createdAt" -> "t.created_at";
            case "ticketNo" -> "t.ticket_no";
            default -> throw new IllegalArgumentException("Unsupported ticket sort: " + sort);
        };
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static UUID uuidFromKey(byte[] key) {
        byte[] first = java.util.Arrays.copyOf(key, 16);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(first);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public enum Scope { REQUESTER, ASSIGNEE, VISIBLE, TENANT }

    // ----- rows -----------------------------------------------------------------------------

    public record CatalogItemRow(UUID catalogItemId, String itemCode, String itemName,
                                 String description, String riskLevel, boolean requiresApproval,
                                 UUID workflowDefinitionId, UUID workflowVersionId,
                                 Integer workflowVersionNo, UUID slaPolicyId, String status,
                                 long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record SlaPolicyRow(UUID slaPolicyId, String policyCode, String policyName,
                               long firstResponseSeconds, long resolutionSeconds,
                               Long firstResponseReminderBeforeSeconds,
                               Long resolutionReminderBeforeSeconds, String status,
                               long lockVersion) {
    }

    public record TicketRow(UUID ticketId, String ticketNo, byte[] creationIntentKey,
                            String originType, UUID catalogItemId, UUID requesterUserId,
                            UUID previousAttemptTicketId, String subject, String description,
                            String status, UUID assigneeUserId, UUID workflowDefinitionId,
                            UUID workflowVersionId, Integer workflowVersionNo,
                            Instant firstProcessableAt, Instant resolvedAt, Instant closedAt,
                            Instant lastReopenedAt, int reopenCount, long lockVersion,
                            Instant createdAt, Instant updatedAt) {
    }

    public record CommentRow(UUID commentId, UUID ticketId, String body, UUID authorUserId,
                             Instant createdAt, UUID correlationId) {
    }

    public record TimelineRow(UUID timelineId, UUID ticketId, byte[] effectKey, String eventType,
                              String summary, String relatedRefType, UUID relatedRefId,
                              String actorType, UUID actorId, Instant occurredAt,
                              UUID correlationId) {
    }

    public record AuditRow(UUID auditId, String actionCode, String targetType,
                           UUID targetId, String outcome, String actorType, UUID actorId,
                           String reason, String errorCode, UUID correlationId,
                           UUID causationId, Instant occurredAt, Instant recordedAt) {
    }

    public record SlaClockRow(UUID slaClockId, UUID ticketId, UUID slaPolicyId, String clockType,
                              String status, Instant startedAt, Instant deadlineAt,
                              Instant reminderDueAt, long policyDurationSeconds,
                              Long policyReminderBeforeSeconds, Instant milestoneAt,
                              Instant breachedAt, Instant satisfiedAt, long lockVersion, int cycleNo) {
    }

    public record SlaTriggerRow(UUID slaClockId, String stage, Instant dueAt, Instant triggeredAt) {
    }

    public record SlaTriggerDetailRow(UUID slaClockId, String stage, byte[] effectKey,
                                      Instant dueAt, Instant triggeredAt, byte[] publicationKey) {
        public SlaTriggerDetailRow {
            effectKey = effectKey == null ? null : effectKey.clone();
            publicationKey = publicationKey == null ? null : publicationKey.clone();
        }
        @Override public byte[] effectKey() { return effectKey == null ? null : effectKey.clone(); }
        @Override public byte[] publicationKey() { return publicationKey == null ? null : publicationKey.clone(); }
    }

    public record SlaCandidateRow(UUID slaClockId, UUID ticketId, String status, String clockType,
                                  Instant deadlineAt, Instant reminderDueAt, Instant updatedAt) {
    }

    public record SlaHighWaterRow(Instant updatedAt, UUID slaClockId) {
    }

    public record SlaEscalationTargetRow(UUID targetId, UUID recipientUserId, String purpose,
                                         String status, Instant effectiveAt,
                                         Long qualificationVersion, Instant qualificationCheckedAt,
                                         Instant qualificationValidUntil, String qualificationState,
                                         String qualificationReasonCode, long lockVersion,
                                         Instant createdAt, Instant updatedAt) {
    }

    public record SlaQualificationRow(UUID targetId, UUID recipientUserId, String purpose,
                                      long qualificationVersion, Instant checkedAt,
                                      Instant validUntil, boolean eligible, String reasonCode) {
    }

    public record SlaRebuildLeaseRow(UUID tenantId, long fencingEpoch, UUID runId,
                                     Instant highWaterUpdatedAt, UUID highWaterSlaClockId,
                                     Instant expiresAt, String state, String lastErrorCode,
                                     long lockVersion, Instant createdAt, Instant updatedAt) {
    }

    public record WorkflowStartRow(UUID ticketId, String status, UUID workflowInstanceId,
                                   Instant requestedAt, Instant establishedAt,
                                   Instant recoveryRequiredAt, String recoveryReasonCode,
                                   int retryCount, Instant lastAttemptAt, long lockVersion,
                                   Instant createdAt, Instant updatedAt) {
    }

    public static final class Uuids {
        private Uuids() {
        }

        public static byte[] toBytes(UUID uuid) {
            if (uuid == null) {
                return null;
            }
            long most = uuid.getMostSignificantBits();
            long least = uuid.getLeastSignificantBits();
            return new byte[]{
                    (byte) (most >>> 56), (byte) (most >>> 48), (byte) (most >>> 40), (byte) (most >>> 32),
                    (byte) (most >>> 24), (byte) (most >>> 16), (byte) (most >>> 8), (byte) most,
                    (byte) (least >>> 56), (byte) (least >>> 48), (byte) (least >>> 40), (byte) (least >>> 32),
                    (byte) (least >>> 24), (byte) (least >>> 16), (byte) (least >>> 8), (byte) least};
        }

        public static UUID toUuid(byte[] bytes) {
            if (bytes == null) {
                return null;
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        }
    }
}
