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
 * 告警发件箱发布、任务抢占与失败恢复持久化接口。
 * 候选扫描采用有界批次，更新以租户、状态、版本和抢占令牌等条件协调并发。
 */
public interface AlertOutboxMapper {
    /**
     * 跨租户扫描指定两类契约、版本 1 的事件发件箱，包含到期 PENDING 与租约过期的 PUBLISHING 消息；按到期时间排序，最多 50 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertOutboxCandidate> findCandidates(@Param("contractRef") String contractRef, @Param("contractRef2") String contractRef2, @Param("max_attempts") int max_attempts, @Param("now") Instant now, @Param("cutoff") Instant cutoff);
    /**
     * 以租户、预期次数和版本抢占到期 PENDING 消息，转为 PUBLISHING，增加尝试次数并写入新 token 和锁定时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimPending(@Param("token") String token, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("attemptCount") int attemptCount, @Param("max_attempts") int max_attempts, @Param("lockVersion") long lockVersion);
    /**
     * 接管锁定时间不晚于 cutoff 的 PUBLISHING 消息，比对旧持有者、版本及次数，成功后更换 token 并增加尝试次数。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimExpired(@Param("token") String token, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("attemptCount") int attemptCount, @Param("max_attempts") int max_attempts, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("cutoff") Instant cutoff);
    /**
     * 抢占到期且尝试次数为 8 的 PENDING 消息，设置发布令牌并增加版本，不再增加尝试次数。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimRecoveryPending(@Param("token") String token, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("lockVersion") long lockVersion);
    /**
     * 接管尝试次数为 8 且租约过期的 PUBLISHING 消息；匹配旧持有者及版本，更换令牌时保持尝试次数不变。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int claimRecoveryExpired(@Param("token") String token, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("lockedBy") String lockedBy, @Param("lockVersion") long lockVersion, @Param("cutoff") Instant cutoff);
    /**
     * 仅在租户、消息、发布状态、次数、token 及版本均匹配时读取已抢占消息，返回发送原文、摘要与追踪字段。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertOutboxClaim> findClaimed(@Param("tenant") UUID tenant, @Param("outbox") UUID outbox, @Param("attempt") int attempt, @Param("token") String token, @Param("version") long version);
    /**
     * 在发布令牌、次数、版本及有效租约匹配时标记 PUBLISHED，记录代理确认时间并清除锁和错误。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int markPublished(@Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("attemptCount") int attemptCount, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Instant cutoff);
    /**
     * 发布成功后，将同租户该发件箱对应的 REPLAYING 恢复记录收敛为 RESOLVED，清除下次执行时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolvePublishedRecovery(@Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId);
    /**
     * 在有效发布租约及版本匹配时释放消息回 PENDING，保存错误码和下一次重试时间，并清除发布锁。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int releasePending(@Param("next") Instant next, @Param("code") String code, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("attemptCount") int attemptCount, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Instant cutoff);
    /**
     * 同步仍为 REQUESTED 的事件工单关联，将重试次数更新为实际发布尝试次数，并记录本次尝试时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int updateLinkRetry(@Param("attemptCount") int attemptCount, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId);
    /**
     * 在第 8 次发布、有效令牌及版本匹配时转为 RECOVERY_REQUIRED，记录错误并释放发布锁。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int markRecoveryRequired(@Param("now") Instant now, @Param("code") String code, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("token") String token, @Param("lockVersion") long lockVersion, @Param("cutoff") Instant cutoff);
    /**
     * 将仍为 REQUESTED 的工单关联转为 RECOVERY_REQUIRED，保存恢复原因、时间及已用尽的 8 次重试。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int markLinkRecoveryRequired(@Param("now") Instant now, @Param("code") String code, @Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId);
    /**
     * 同一发件箱及业务意图的重放再次用尽时，将 REPLAYING 恢复记录重开为 OPEN，保存错误并清除已解决时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int reopenRecovery(@Param("code") String code, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId, @Param("intent") byte[] intent);
    /**
     * 为发布用尽的发件箱建立 OUTBOX 方向 OPEN 恢复记录，尝试次数为 8，并记录源消息和业务意图。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertRecovery(@Param("tenantId") UUID tenantId, @Param("newId") UUID newId, @Param("outboxId") UUID outboxId, @Param("intent") byte[] intent, @Param("code") String code, @Param("now") Instant now);
    /**
     * 按租户和发件箱标识查询 OUTBOX 恢复记录的业务意图及状态，供登记恢复时识别已有记录。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertOutboxRecovery> findRecovery(@Param("tenantId") UUID tenantId, @Param("outboxId") UUID outboxId);
    /**
     * 保存告警发件箱需要恢复的失败审计，目标类型为 MESSAGE_RECOVERY，记录源发件箱、错误码及关联标识。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertRecoveryAudit(@Param("tenantId") UUID tenantId, @Param("newId") UUID newId, @Param("outboxId") UUID outboxId, @Param("code") String code, @Param("correlationId") UUID correlationId, @Param("now") Instant now);
}
