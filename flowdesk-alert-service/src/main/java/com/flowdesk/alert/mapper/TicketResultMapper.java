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
 * 工单创建结果消费与告警建单关联持久化接口。
 * 保存收件箱幂等依据、建立工单关联，并维护失败恢复记录和审计。
 */
public interface TicketResultMapper {
    /**
     * 在关联为 REQUESTED 或 RECOVERY_REQUIRED 且版本匹配时绑定工单，转为 ESTABLISHED 并清除恢复原因。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int establishLink(@Param("ticketId") UUID ticketId, @Param("establishedAt") Instant establishedAt, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId, @Param("lockVersion") long lockVersion);
    /**
     * 收到有效工单结果后，解决同一事件实例、指定契约及版本 1 发件箱关联的 OPEN 或 REPLAYING 恢复记录。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolveOutboxRecoveries(@Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("incidentId") UUID incidentId, @Param("contractRef") String contractRef);
    /**
     * 保存工单结果的消息标识、业务意图、原文摘要及消费结果，初始化版本并记录处理次数及时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertInbox(@Param("tenantId") UUID tenantId, @Param("newId") UUID newId, @Param("producer") String producer, @Param("messageId") UUID messageId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("contractRef") String contractRef, @Param("bodyHash") byte[] bodyHash, @Param("body") byte[] body, @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId, @Param("envelopeOccurredAt") Instant envelopeOccurredAt, @Param("status") String status, @Param("resultType") String resultType, @Param("result") UUID result, @Param("attempts") int attempts, @Param("error") String error, @Param("now") Instant now, @Param("processedAt") Instant processedAt);
    /**
     * 在收件箱为 RECOVERY_REQUIRED 且版本匹配时记录结果，转为 PROCESSED，增加尝试次数并清除错误。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int completeRecoveredInbox(@Param("result") UUID result, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("version") long version);
    /**
     * 在收件箱为 RECOVERY_REQUIRED 且版本匹配时转为 REJECTED，清除结果、保存拒绝原因并增加次数。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int rejectRecoveredInbox(@Param("error") String error, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("version") long version);
    /**
     * 为用尽 6 次投递的工单结果建立 INBOX 方向 OPEN 恢复记录，保存死信及需要恢复的时间。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertRecovery(@Param("tenantId") UUID tenantId, @Param("newId") UUID newId, @Param("inbox") UUID inbox, @Param("businessIntentKey") byte[] businessIntentKey, @Param("code") String code, @Param("now") Instant now);
    /**
     * 保存待恢复的工单结果原文和摘要，初始为 RECOVERY_REQUIRED、尝试次数为 6，供后续同路径重放。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertRecoveryInbox(@Param("tenantId") UUID tenantId, @Param("inbox") UUID inbox, @Param("producer") String producer, @Param("messageId") UUID messageId, @Param("businessIntentKey") byte[] businessIntentKey, @Param("contractRef") String contractRef, @Param("bodyHash") byte[] bodyHash, @Param("body") byte[] body, @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId, @Param("envelopeOccurredAt") Instant envelopeOccurredAt, @Param("error") String error, @Param("now") Instant now);
    /**
     * 以服务主体记录工单结果消费审计，目标为事件实例，消息标识作为因果标识。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertAudit(@Param("tenantId") UUID tenantId, @Param("newId") UUID newId, @Param("action") String action, @Param("incidentId") UUID incidentId, @Param("outcome") String outcome, @Param("error") String error, @Param("correlationId") UUID correlationId, @Param("messageId") UUID messageId, @Param("now") Instant now);
    /**
     * 源消息处理完成后，将同租户及源收件箱的 OPEN 或 REPLAYING 恢复记录标记为已解决。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int resolveInboxRecovery(@Param("now") Instant now, @Param("tenant") UUID tenant, @Param("inbox") UUID inbox);
    /**
     * 按租户、生产服务及消息标识读取收件箱，供传输投递去重；lock 为 true 时加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<TicketResultInbox> findByMessage(@Param("tenant") UUID tenant, @Param("producer") String producer, @Param("message") UUID message, @Param("lock") boolean lock);
    /**
     * 按租户、生产服务、契约及业务意图读取收件箱，供业务意图去重；lock 为 true 时加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<TicketResultInbox> findByIntent(@Param("tenant") UUID tenant, @Param("producer") String producer, @Param("contract") String contract, @Param("intent") byte[] intent, @Param("lock") boolean lock);
}
