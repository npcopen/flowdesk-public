package com.flowdesk.notification.mapper;
import com.flowdesk.notification.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.notification.event.*;
import com.flowdesk.notification.recovery.*;

/**
 * 通知收件箱持久化接口，保存消费结果并提供消息、业务意图及发布标识查询。
 * 普通查询只返回元数据和摘要，重放所需完整消息体由独立恢复数据源读取。
 */
public interface NotificationInboxMapper {
    /**
     * 按租户、生产服务及消息标识读取收件箱，供传输投递去重；返回元数据及摘要，原文列为空，lock 控制行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationInboxRow> findByMessage(@Param("tenantId") UUID tenantId, @Param("producerService") String producerService, @Param("messageId") UUID messageId, @Param("lock") boolean lock);
    /**
     * 按租户、生产服务、契约及业务意图查询收件箱，供业务去重；返回元数据及摘要，原文列为空，lock 控制行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationInboxRow> findByIntent(@Param("tenantId") UUID tenantId, @Param("producerService") String producerService, @Param("contractRef") String contractRef, @Param("businessIntentKey") byte[] businessIntentKey, @Param("lock") boolean lock);
    /**
     * 保存消费消息、路由、契约、发布键与业务意图，并记录原文摘要、处理结果、次数及时间，初始版本为 0。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int insertInbox(@Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("producerService") String producerService, @Param("messageId") UUID messageId, @Param("messageKey") String messageKey, @Param("businessIntentKey") byte[] businessIntentKey, @Param("publicationKey") byte[] publicationKey, @Param("contractRef") String contractRef, @Param("version") int version, @Param("aggregateType") String aggregateType, @Param("aggregateId") UUID aggregateId, @Param("bodySha256") byte[] bodySha256, @Param("bodyBytes") byte[] bodyBytes, @Param("topic") String topic, @Param("consumerGroup") String consumerGroup, @Param("logicalDestination") String logicalDestination, @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId, @Param("occurredAt") Instant occurredAt, @Param("status") String status, @Param("resultType") String resultType, @Param("resultId") UUID resultId, @Param("attempts") int attempts, @Param("error") String error, @Param("now") Instant now, @Param("processedAt") Instant processedAt);
    /**
     * 在 RECOVERY_REQUIRED 状态且版本匹配时记录通知结果，转为 PROCESSED，清除错误并增加一次尝试。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int completeRecoveredInbox(@Param("resultId") UUID resultId, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 在 RECOVERY_REQUIRED 状态且版本匹配时转为 REJECTED，保存错误及处理时间并增加一次尝试。
     *
     * @return 实际影响的记录行数，调用方据此判断条件更新或写入是否生效。
     */
    int rejectRecoveredInbox(@Param("code") String code, @Param("now") Instant now, @Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("lockVersion") long lockVersion);
    /**
     * 按租户及发布键查询收件箱元数据和摘要，供发布标识去重；不返回消息原文，lock 控制行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationInboxRow> findByPublication(@Param("tenantId") UUID tenantId, @Param("publicationKey") byte[] publicationKey, @Param("lock") boolean lock);
    /**
     * 按租户及收件箱标识读取元数据与摘要，不返回消息原文；lock 为 true 时加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationInboxRow> findById(@Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("lock") boolean lock);
}
