package com.flowdesk.common.recovery;

import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 各服务共用的收件箱恢复重放 SQL 契约，由服务侧子 Mapper 绑定相应数据库访问配置。
 * 负责发现已获人工重放授权的到期记录，并以恢复记录锁版本和本次关联 ID 竞争执行租约。
 * 候选扫描跨租户进行，实际领取则使用候选携带的 tenantId、recoveryId 精确限定记录。
 */
public interface RecoveryReplayMapper {
    /**
     * 扫描处于 REPLAYING 的收件箱恢复记录，每批最多返回 32 条，按到期时间、租户和记录 ID 排序。
     * 仅选择带人工操作人及重放关联 ID，且 next_attempt_at 为空或不晚于 now 的记录；
     * 同时左连接原收件消息，读取其关联 ID。此查询不加锁，需通过 claim 竞争领取。
     */
    @Select("""
            SELECT r.tenant_id,r.recovery_id,r.source_record_id,r.last_replayed_by_id,
                   r.last_replay_reason,r.last_replay_correlation_id,r.lock_version,
                   i.correlation_id
            FROM message_recovery r
            LEFT JOIN inbox_message i ON i.tenant_id=r.tenant_id AND i.inbox_id=r.source_record_id
            WHERE r.direction='INBOX' AND r.state='REPLAYING'
              AND r.last_replayed_by_type='USER' AND r.last_replayed_by_id IS NOT NULL
              AND r.last_replay_correlation_id IS NOT NULL
              AND (r.next_attempt_at IS NULL OR r.next_attempt_at<=#{now})
            ORDER BY r.next_attempt_at,r.tenant_id,r.recovery_id
            LIMIT 32
            """)
    @ConstructorArgs({
            @Arg(column = "tenant_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "recovery_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "source_record_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "last_replayed_by_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "last_replay_reason", javaType = String.class),
            @Arg(column = "last_replay_correlation_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "correlation_id", javaType = UUID.class, typeHandler = UuidBinaryTypeHandler.class),
            @Arg(column = "lock_version", javaType = long.class)
    })
    List<RecoveryReplayCandidate> findDue(@Param("now") Instant now);

    /**
     * 为候选恢复记录竞争租约，将 next_attempt_at 推迟至 until 并递增锁版本。
     * 必须仍为到期的 INBOX/REPLAYING 记录，且版本、本次重放关联 ID 与 candidate 一致。
     * 返回受影响行数：1 表示领取成功，0 表示已被其他执行者领取或候选状态已经变化。
     */
    @Update("""
            UPDATE message_recovery SET next_attempt_at=#{until},lock_version=lock_version+1,
                updated_at=#{now},updated_by_type='SERVICE',updated_by_id=NULL
            WHERE tenant_id=#{candidate.tenantId} AND recovery_id=#{candidate.recoveryId}
              AND direction='INBOX' AND state='REPLAYING'
              AND last_replay_correlation_id=#{candidate.attemptCorrelationId}
              AND lock_version=#{candidate.lockVersion}
              AND (next_attempt_at IS NULL OR next_attempt_at<=#{now})
            """)
    int claim(@Param("candidate") RecoveryReplayCandidate candidate,
              @Param("until") Instant until, @Param("now") Instant now);
}
