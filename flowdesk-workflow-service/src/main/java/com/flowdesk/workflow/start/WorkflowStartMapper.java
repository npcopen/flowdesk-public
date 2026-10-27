package com.flowdesk.workflow.start;

import com.flowdesk.workflow.model.start.WorkflowStartModels;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

/**
 * 工作流启动消息消费与实例建立的持久化接口。
 * 按租户保存收件箱幂等事实、版本快照引用、实例及首级任务，并提供发件箱、审计与失败恢复所需的底层操作。
 */
@Mapper
public interface WorkflowStartMapper {
    /**
     * 按租户、生产服务和消息标识读取收件箱事实。
     * 用于消息级幂等核对；lock 为 true 时追加 FOR UPDATE，供事务内安全读取处理状态。
     */
    List<WorkflowStartModels.InboxRecord> findInboxByMessage(@Param("messageTenantId") byte[] messageTenantId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("lock") boolean lock);

    /**
     * 按生产服务、契约和业务意图键读取租户内收件箱。
     * 用于识别消息标识不同但业务意图相同的投递；lock 为 true 时锁定匹配记录。
     */
    List<WorkflowStartModels.InboxRecord> findInboxByIntent(@Param("messageTenantId") byte[] messageTenantId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("lock") boolean lock);

    /**
     * 读取启动消息明确引用的工作流版本快照。
     * 同时匹配租户、定义标识、版本标识和版本号，联查定义状态，供调用方验证版本与定义是否可用。
     */
    List<WorkflowStartModels.VersionSnapshot> findVersion(@Param("messageTenantId") byte[] messageTenantId,
            @Param("messageWorkflowVersionId") byte[] messageWorkflowVersionId,
            @Param("messageWorkflowDefinitionId") byte[] messageWorkflowDefinitionId,
            @Param("messageVersionNo") int messageVersionNo);

    /**
     * 保存已成功建立工作流的收件箱消息。
     * 保留原始正文、摘要及消息身份，状态设为 PROCESSED，以 WORKFLOW_INSTANCE 关联建立的实例。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertProcessedInbox(@Param("messageTenantId") byte[] messageTenantId,
            @Param("inboxId") byte[] inboxId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageContractVersion") int messageContractVersion,
            @Param("messageBodyBytes") byte[] messageBodyBytes,
            @Param("messageBodySha256") byte[] messageBodySha256,
            @Param("messageCorrelationId") byte[] messageCorrelationId,
            @Param("messageCausationId") byte[] messageCausationId,
            @Param("messageOccurredAt") Timestamp messageOccurredAt,
            @Param("workflowInstanceId") byte[] workflowInstanceId,
            @Param("now") Timestamp now);

    /**
     * 保存已判定拒绝处理的启动消息。
     * 写入 REJECTED、尝试次数、错误码与处理完成时间，同时保留完整消息用于查询与核对。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertRejectedInbox(@Param("messageTenantId") byte[] messageTenantId,
            @Param("inboxId") byte[] inboxId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageContractVersion") int messageContractVersion,
            @Param("messageBodyBytes") byte[] messageBodyBytes,
            @Param("messageBodySha256") byte[] messageBodySha256,
            @Param("messageCorrelationId") byte[] messageCorrelationId,
            @Param("messageCausationId") byte[] messageCausationId,
            @Param("messageOccurredAt") Timestamp messageOccurredAt,
            @Param("attemptCount") int attemptCount,
            @Param("errorCode") String errorCode,
            @Param("now") Timestamp now);

    /**
     * 保存需要人工恢复的启动消息。
     * 记录 RECOVERY_REQUIRED、投递次数和错误码，保留正文及幂等身份，但不设置处理完成时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertRecoveryRequiredInbox(@Param("messageTenantId") byte[] messageTenantId,
            @Param("inboxId") byte[] inboxId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageContractVersion") int messageContractVersion,
            @Param("messageBodyBytes") byte[] messageBodyBytes,
            @Param("messageBodySha256") byte[] messageBodySha256,
            @Param("messageCorrelationId") byte[] messageCorrelationId,
            @Param("messageCausationId") byte[] messageCausationId,
            @Param("messageOccurredAt") Timestamp messageOccurredAt,
            @Param("deliveryAttempts") int deliveryAttempts,
            @Param("errorCode") String errorCode,
            @Param("now") Timestamp now);

    /**
     * 查找指定收件箱对应的恢复记录。
     * 限定租户及 INBOX 方向，返回恢复状态、锁版本与最近重放关联标识；可选 FOR UPDATE。
     */
    List<WorkflowStartModels.RecoveryRecord> findInboxRecovery(@Param("tenantId") byte[] tenantId,
            @Param("inboxId") byte[] inboxId,
            @Param("lock") boolean lock);

    /**
     * 为失败的启动消息新增开放的收件箱恢复记录。
     * 关联 inboxId 与业务意图键，记录投递次数和错误摘要，死信时间及需要恢复时间均设为 now。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertInboxRecovery(@Param("messageTenantId") byte[] messageTenantId,
            @Param("recoveryId") byte[] recoveryId,
            @Param("inboxId") byte[] inboxId,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("deliveryAttempts") int deliveryAttempts,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("now") Timestamp now);

    /**
     * 将重放成功的收件箱更新为已处理并关联实例。
     * 核对租户、消息身份、契约、业务意图、正文摘要及锁版本，仅迁移 RECOVERY_REQUIRED 记录。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int completeRecoveryInbox(@Param("workflowInstanceId") byte[] workflowInstanceId,
            @Param("now") Timestamp now,
            @Param("messageTenantId") byte[] messageTenantId,
            @Param("inboxInboxId") byte[] inboxInboxId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageContractVersion") int messageContractVersion,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageBodySha256") byte[] messageBodySha256,
            @Param("inboxLockVersion") long inboxLockVersion);

    /**
     * 将重放后确认拒绝的收件箱更新为 REJECTED。
     * 核对完整消息身份、正文摘要和锁版本，增加尝试次数，记录错误码及处理完成时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int rejectRecoveryInbox(@Param("errorCode") String errorCode,
            @Param("now") Timestamp now,
            @Param("messageTenantId") byte[] messageTenantId,
            @Param("inboxInboxId") byte[] inboxInboxId,
            @Param("messageProducerService") String messageProducerService,
            @Param("messageMessageId") byte[] messageMessageId,
            @Param("messageContractRef") String messageContractRef,
            @Param("messageContractVersion") int messageContractVersion,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageBodySha256") byte[] messageBodySha256,
            @Param("inboxLockVersion") long inboxLockVersion);

    /**
     * 在收件箱处理结束后解决对应恢复记录。
     * 限定租户、来源收件箱、INBOX 方向及锁版本，仅允许 OPEN 或 REPLAYING 转为 RESOLVED。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int resolveInboxRecovery(@Param("now") Timestamp now,
            @Param("tenantId") byte[] tenantId,
            @Param("recoveryRecoveryId") byte[] recoveryRecoveryId,
            @Param("inboxId") byte[] inboxId,
            @Param("recoveryLockVersion") long recoveryLockVersion);

    /**
     * 保存启动消息创建的工作流实例及输入快照。
     * 固定引用消息携带的定义版本，保存业务意图、条件结果、状态和级别；完成时间由调用方按流程传入。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertInstance(@Param("messageTenantId") byte[] messageTenantId,
            @Param("workflowInstanceId") byte[] workflowInstanceId,
            @Param("messageTicketId") byte[] messageTicketId,
            @Param("messageBusinessIntentKey") byte[] messageBusinessIntentKey,
            @Param("messageWorkflowDefinitionId") byte[] messageWorkflowDefinitionId,
            @Param("messageWorkflowVersionId") byte[] messageWorkflowVersionId,
            @Param("messageVersionNo") int messageVersionNo,
            @Param("inputSnapshotJson") String inputSnapshotJson,
            @Param("inputSnapshotSha256") byte[] inputSnapshotSha256,
            @Param("conditionResult") boolean conditionResult,
            @Param("status") String status,
            @Param("currentLevel") Integer currentLevel,
            @Param("now") Timestamp now,
            @Param("completedAt") Timestamp completedAt);

    /**
     * 为新实例创建一级待审批任务。
     * 写入租户、任务标识、实例和一级审批人，级别固定为 1，初始状态为 PENDING。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertFirstLevelTask(@Param("tenantId") byte[] tenantId,
            @Param("taskId") byte[] taskId,
            @Param("workflowInstanceId") byte[] workflowInstanceId,
            @Param("approverUserId") byte[] approverUserId);

    /**
     * 保存工作流实例建立事件的本地发布记录。
     * 记录消息及发布身份、正文摘要和关联链路，使用契约版本 1，初始化为 PENDING、尝试次数 0。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertInstanceEstablishedOutbox(@Param("tenantId") byte[] tenantId,
            @Param("outboxId") byte[] outboxId,
            @Param("messageId") byte[] messageId,
            @Param("publicationKey") byte[] publicationKey,
            @Param("businessIntentKey") byte[] businessIntentKey,
            @Param("workflowInstanceId") byte[] workflowInstanceId,
            @Param("contractRef") String contractRef,
            @Param("body") byte[] body,
            @Param("bodySha256") byte[] bodySha256,
            @Param("correlationId") byte[] correlationId,
            @Param("causationId") byte[] causationId,
            @Param("now") Timestamp now);

    /**
     * 记录启动消息处理过程产生的服务审计。
     * 操作人类型固定为 SERVICE，目标、结果、原因和错误码由调用方提供，保留消息关联与因果标识。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int insertAudit(@Param("messageTenantId") byte[] messageTenantId,
            @Param("auditId") byte[] auditId,
            @Param("actionCode") String actionCode,
            @Param("targetType") String targetType,
            @Param("targetId") byte[] targetId,
            @Param("outcome") String outcome,
            @Param("reason") String reason,
            @Param("errorCode") String errorCode,
            @Param("messageCorrelationId") byte[] messageCorrelationId,
            @Param("messageCausationId") byte[] messageCausationId,
            @Param("now") Timestamp now);
    /**
     * 按工单或启动业务意图查询租户内已有实例。
     * byTicket 为 true 时按 ticket_id 匹配，否则按 start_intent_key 匹配；lock 可为幂等处理申请行锁。
     */
    List<WorkflowStartModels.InstanceRecord> findInstances(@Param("tenantId") byte[] tenantId,
            @Param("value") byte[] value, @Param("byTicket") boolean byTicket,
            @Param("lock") boolean lock);

    /**
     * 统计历史上因审批人不具资格而拒绝的启动消息。
     * 扫描工作流启动 v1 契约下的 REJECTED 记录并匹配指定错误码，该维护查询不按租户筛选。
     */
    Integer countLegacyIamDenials();
}
