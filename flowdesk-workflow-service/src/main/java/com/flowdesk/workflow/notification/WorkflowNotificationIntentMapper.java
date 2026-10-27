package com.flowdesk.workflow.notification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.UUID;

/**
 * 工作流通知意图的本地发件箱写入接口。
 * 在调用方业务事务内保存通知正文、摘要及幂等身份，为异步通知投递保留完整消息事实。
 */
@Mapper
public interface WorkflowNotificationIntentMapper {
    /**
     * 写入工作流任务对应的通知请求消息。
     * 保存 publicationKey、businessIntentKey、契约版本、正文和 SHA-256 摘要，初始化为可立即领取的 PENDING 记录。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertNotificationOutbox(@Param("tenantId") UUID tenantId,
            @Param("outboxId") UUID outboxId, @Param("messageId") UUID messageId,
            @Param("publicationKey") byte[] publicationKey,
            @Param("businessIntentKey") byte[] businessIntentKey,
            @Param("taskId") UUID taskId, @Param("contractVersion") int contractVersion,
            @Param("bodyBytes") byte[] bodyBytes, @Param("bodySha256") byte[] bodySha256,
            @Param("correlationId") UUID correlationId, @Param("causationId") UUID causationId,
            @Param("now") Instant now);
}
