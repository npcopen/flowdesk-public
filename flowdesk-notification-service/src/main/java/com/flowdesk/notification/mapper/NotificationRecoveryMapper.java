package com.flowdesk.notification.mapper;
import com.flowdesk.notification.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.notification.event.*;
import com.flowdesk.notification.recovery.*;

/**
 * 通知收件箱恢复记录持久化接口，维护人工重放、失败重开及终态收敛。
 * 查询限定租户，状态流转以预期版本控制并发，消息原文由独立恢复 Mapper 读取。
 */
public interface NotificationRecoveryMapper {
    /**
     * 为收件箱登记 INBOX 方向 OPEN 恢复记录，保留实际投递次数、业务意图及错误摘要，初始化重放次数和版本。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertRecovery(@Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("inboxId") UUID inboxId, @Param("businessIntent") byte[] businessIntent, @Param("delivery_attempts") int delivery_attempts, @Param("errorCode") String errorCode, @Param("summary") String summary, @Param("now") Instant now);
    /**
     * 按租户及恢复标识读取详情，lock 为 true 时使用 FOR UPDATE，供同一事务内重放决策。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationRecoveryRow> findRecovery(@Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("lock") boolean lock);
    /**
     * 以 OPEN 状态及 expectedVersion 领取人工重放，转为 REPLAYING，保存租约、操作人、理由及本次重放关联标识。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimReplay(@Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now, @Param("actorId") UUID actorId, @Param("reason") String reason, @Param("replayCorrelation") UUID replayCorrelation, @Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("expectedVersion") long expectedVersion);
    /**
     * 在 OPEN 或 REPLAYING 状态且版本匹配时解决记录，记录解决时间并清除下次执行时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolveRecovery(@Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("expectedVersion") long expectedVersion);
    /**
     * 仅在对应 INBOX 源消息已为 PROCESSED 或 REJECTED，且恢复状态及版本匹配时收敛为 RESOLVED。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolveIfSourceTerminal(@Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("expectedVersion") long expectedVersion);
    /**
     * 在 REPLAYING 状态且版本匹配时重开为 OPEN，更新错误码及摘要并清除租约时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int reopenRecovery(@Param("errorCode") String errorCode, @Param("summary") String summary, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("expectedVersion") long expectedVersion);
    /**
     * 在 OPEN 或 REPLAYING 状态且版本匹配时终结不可重放记录，保留错误码及摘要说明原因。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int markNonReplayable(@Param("now") Instant now, @Param("errorCode") String errorCode, @Param("summary") String summary, @Param("tenantId") UUID tenantId, @Param("recoveryId") UUID recoveryId, @Param("expectedVersion") long expectedVersion);
    /**
     * 按租户及源收件箱读取 INBOX 恢复记录，供登记恢复时识别已有记录；lock 控制行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationRecoveryRow> findRecoveryBySource(@Param("tenantId") UUID tenantId, @Param("sourceRecordId") UUID sourceRecordId, @Param("lock") boolean lock);
    /**
     * 查询租户内 INBOX 恢复记录，按方向、状态、死信、错误及时间筛选，以恢复要求时间和恢复标识进行稳定游标分页。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationRecoveryRow> listRecoveries(@Param("tenantId") UUID tenantId, @Param("direction") String direction, @Param("state") String state, @Param("deadLettered") Boolean deadLettered, @Param("errorCode") String errorCode, @Param("from") Instant from, @Param("to") Instant to, @Param("ascending") boolean ascending, @Param("cursorValue") Instant cursorValue, @Param("cursorId") UUID cursorId, @Param("limit") int limit);
}
