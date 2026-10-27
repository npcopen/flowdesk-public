package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.alert.event.*;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.webhook.*;
import com.flowdesk.alert.service.*;

/**
 * 告警消息恢复、源消息读取与人工重放审计持久化接口。
 * 状态流转受版本或重放租约约束，业务层负责组织同路径重放事务。
 */
public interface AlertRecoveryMapper {
    /**
     * 仅在重放关联标识和租约时间匹配、且源收件箱已为 PROCESSED 或 REJECTED 时，将 REPLAYING 恢复记录收敛为 RESOLVED。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolveTerminal(@Param("now") Instant now, @Param("tenant") UUID tenant, @Param("id") UUID id, @Param("correlation") UUID correlation, @Param("lease") Instant lease);
    /**
     * 按租户及恢复标识读取详情；lock 为 true 时追加 FOR UPDATE，供重放决策及更新共享事务行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertRecoveryRow> findRecovery(@Param("tenant") UUID tenant, @Param("id") UUID id, @Param("lock") boolean lock);
    /**
     * 按租户及收件箱标识读取重放原文、契约、摘要与当前状态，lock 控制是否对源消息加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertRecoveryInbox> findInbox(@Param("tenant") UUID tenant, @Param("id") UUID id, @Param("lock") boolean lock);
    /**
     * 按租户及发件箱标识读取源消息和事件工单关联状态，并携带二者版本；lock 为 true 时追加 FOR UPDATE。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertRecoveryOutbox> findOutbox(@Param("tenant") UUID tenant, @Param("id") UUID id, @Param("lock") boolean lock);
    /**
     * 在源消息仍为 RECOVERY_REQUIRED 且版本匹配时重置为 PENDING，清空锁、确认及错误，从 0 次尝试重新调度。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resetOutbox(@Param("now") Instant now, @Param("tenant") UUID tenant, @Param("outboxId") UUID outboxId, @Param("version") long version);
    /**
     * 在工单关联仍为 RECOVERY_REQUIRED 且版本匹配时恢复为 REQUESTED，清除恢复原因及时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resetLink(@Param("now") Instant now, @Param("tenant") UUID tenant, @Param("incidentId") UUID incidentId, @Param("linkVersion") long linkVersion);
    /**
     * 以租户、方向、源记录及预期版本领取 OPEN 恢复任务，转为 REPLAYING，保存租约、人工操作人、理由及本次关联标识。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimReplay(@Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now, @Param("actor") UUID actor, @Param("reason") String reason, @Param("corr") UUID corr, @Param("tenant") UUID tenant, @Param("id") UUID id, @Param("direction") String direction, @Param("source") UUID source, @Param("version") long version);
    /**
     * 同路径重放未到达本地终态时，匹配重放关联标识及租约，将 REPLAYING 记录重开并保存错误。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int reopen(@Param("error") String error, @Param("now") Instant now, @Param("tenant") UUID tenant, @Param("id") UUID id, @Param("corr") UUID corr, @Param("leaseUntil") Instant leaseUntil);
    /**
     * 按租户与审计标识读取重放审计的动作、目标、结果、理由及追踪信息。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertRecoveryAudit> findAudit(@Param("tenant") UUID tenant, @Param("id") UUID id);
    /**
     * 以人工用户为主体写入 MESSAGE_RECOVERY 审计，记录恢复目标、执行结果、理由及关联和因果标识。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertAudit(@Param("tenant") UUID tenant, @Param("id") UUID id, @Param("action") String action, @Param("recovery") UUID recovery, @Param("outcome") String outcome, @Param("actor") UUID actor, @Param("reason") String reason, @Param("error") String error, @Param("corr") UUID corr, @Param("cause") UUID cause, @Param("now") Instant now);
    /**
     * 按方向、状态、死信、错误及恢复时间筛选；可按死信或恢复要求时间排序，以选定时间及恢复标识进行稳定游标分页。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertRecoveryRow> listRecoveries(@Param("tenant") UUID tenant, @Param("direction") String direction, @Param("state") String state, @Param("dead") Boolean dead, @Param("error") String error, @Param("from") Instant from, @Param("to") Instant to, @Param("asc") boolean asc, @Param("deadSort") boolean deadSort, @Param("cursor") Instant cursor, @Param("cursorId") UUID cursorId, @Param("limit") int limit);
}
