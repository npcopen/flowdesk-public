package com.flowdesk.workflow.mapper;

import com.flowdesk.workflow.model.persistence.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流实例、审批任务与审批人变更的运行态持久化接口。
 * 按租户查询运行事实，以任务状态、审批人和锁版本约束状态迁移，并提供事务内加锁及游标分页。
 */
@Mapper
public interface WorkflowRuntimeMapper {
    /**
     * 为当前审批人的待办任务写入审批决定。
     * 同时匹配租户、PENDING、actorId 和 expectedVersion，保存决定类型、原因及 decisionIntentKey，并递增锁版本。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int decideTask(@Param("tenantId") UUID tenantId, @Param("taskId") UUID taskId, @Param("expectedVersion") long expectedVersion, @Param("status") String status, @Param("decisionType") String decisionType, @Param("reason") String reason, @Param("decisionIntentKey") byte[] decisionIntentKey, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 保存一次审批人变更的事实记录。
     * 记录租户、实例、级别、变更前后审批人、操作者和原因；digest 保存请求摘要以供后续幂等核对。
     */
    void insertApproverChange(@Param("tenantId") UUID tenantId, @Param("changeId") UUID changeId, @Param("instanceId") UUID instanceId, @Param("levelNo") int levelNo, @Param("previous") UUID previous, @Param("next") UUID next, @Param("actorId") UUID actorId, @Param("reason") String reason, @Param("digest") String digest, @Param("now") Instant now);

    /**
     * 将尚未决策的审批任务改派给指定审批人。
     * 仅更新租户内 task 标识对应且仍为 PENDING、锁版本与快照一致的任务，并递增锁版本。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int reassignPendingTask(@Param("tenantId") UUID tenantId, @Param("task") TaskRow task, @Param("next") UUID next, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 变更审批人时更新运行中实例的并发版本。
     * 仅在 RUNNING 和 expectedVersion 匹配时递增锁版本，记录人工操作者与时间。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int touchInstanceForApproverChange(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId, @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 将运行中的工作流从一级审批推进至二级。
     * 限定 current_level 为 1 且锁版本匹配，推进后递增版本；二级任务由独立写入操作创建。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int advanceInstanceToLevel2(@Param("tenantId") UUID tenantId, @Param("workflowInstanceId") UUID workflowInstanceId, @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 为指定实例创建二级待审批任务。
     * 写入 level_no=2、PENDING 和初始锁版本 0，以服务身份记录创建、更新时间。
     */
    void insertSecondLevelTask(@Param("tenantId") UUID tenantId, @Param("taskId") UUID taskId, @Param("workflowInstanceId") UUID workflowInstanceId, @Param("approverUserId") UUID approverUserId, @Param("now") Instant now);

    /**
     * 将运行中实例更新为调用方给定的终结状态。
     * 通过 expectedVersion 控制并发，清空当前级别，保存完成时间并递增锁版本。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int completeInstance(@Param("tenantId") UUID tenantId, @Param("workflowInstanceId") UUID workflowInstanceId, @Param("expectedVersion") long expectedVersion, @Param("status") String status, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按租户、状态、工单或版本分页查询实例。
     * 可按曾分配到任务的审批人限定范围，按开始时间和实例标识稳定排序，最多返回 pageSize + 1 条。
     */
    List<InstanceRow> findInstances(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("ticketId") UUID ticketId, @Param("workflowVersionId") UUID workflowVersionId, @Param("approverUserId") UUID approverUserId, @Param("ascending") boolean ascending, @Param("cursorStartedAt") Instant cursorStartedAt, @Param("cursorId") UUID cursorId, @Param("pageSize") int pageSize);

    /**
     * 读取租户内指定实例及其启动消息信息。
     * approverUserId 非空时要求存在该审批人的关联任务；lock 为 true 时追加 FOR UPDATE。
     */
    Optional<InstanceRow> findInstance(@Param("tenantId") UUID tenantId, @Param("workflowInstanceId") UUID workflowInstanceId, @Param("approverUserId") UUID approverUserId, @Param("lock") boolean lock);

    /**
     * 按租户和可选状态、级别、实例、审批人分页查询任务。
     * 按创建时间或决定时间与任务标识进行游标排序；决定时间为空时使用创建时间，最多返回 pageSize + 1 条。
     */
    List<TaskRow> findTasks(@Param("tenantId") UUID tenantId, @Param("status") String status, @Param("levelNo") Integer levelNo, @Param("workflowInstanceId") UUID workflowInstanceId, @Param("approverUserId") UUID approverUserId, @Param("sort") String sort, @Param("cursorValue") String cursorValue, @Param("cursorId") UUID cursorId, @Param("pageSize") int pageSize);

    /**
     * 统计租户内处于 PENDING 状态的审批任务。
     * approverUserId 非空时仅统计该审批人的任务；为空时统计整个租户。
     */
    long countPendingTasks(@Param("tenantId") UUID tenantId, @Param("approverUserId") UUID approverUserId);

    /**
     * 按租户与任务标识读取审批任务。
     * lock 为 true 时通过 FOR UPDATE 锁定记录；不存在时返回空 Optional。
     */
    Optional<TaskRow> findTask(@Param("tenantId") UUID tenantId, @Param("taskId") UUID taskId, @Param("lock") boolean lock);

    /**
     * 查询指定实例当前的待审批任务。
     * 按租户、实例和 PENDING 状态定位，不读取已完成的历史决定。
     */
    Optional<TaskRow> findPendingTask(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId);

    /**
     * 查询实例二级审批的当前有效审批人。
     * 优先采用实例级覆盖值，否则使用实例所引用定义版本中的二级审批人。
     */
    Optional<UUID> findLevel2Approver(@Param("tenantId") UUID tenantId, @Param("workflowInstanceId") UUID workflowInstanceId);

    /**
     * 从已创建的一级任务读取实例的一级审批人。
     * 按租户、实例和 level_no=1 定位，可反映一级任务改派后的审批人。
     */
    Optional<UUID> findLevel1Approver(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId);

    /**
     * 按审批级别读取实例当前生效的审批人。
     * levelNo 为 2 时查询二级覆盖或版本配置，其他值沿用一级任务查询；级别合法性由调用方保证。
     */
    default Optional<UUID> findEffectiveApprover(UUID tenantId, UUID instanceId, int levelNo) {
        return levelNo == 2 ? findLevel2Approver(tenantId, instanceId) : findLevel1Approver(tenantId, instanceId);
    }

    /**
     * 按租户和变更标识读取审批人变更事实。
     * 包含原审批人、新审批人、请求摘要及原因；不存在时返回空 Optional。
     */
    Optional<ApproverChangeRow> findApproverChange(@Param("tenantId") UUID tenantId, @Param("changeId") UUID changeId);

    /**
     * 读取指定实例最近的审批人变更记录。
     * 按变更时间、变更标识倒序排列，最多返回 100 条。
     */
    List<ApproverChangeRow> findApproverChanges(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId);

    /**
     * 更新实例指定级别已存在的审批人覆盖记录。
     * 按租户、实例和级别定位；无记录时返回 0，供调用方决定是否补充插入。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int updateApproverOverride(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId, @Param("levelNo") int levelNo, @Param("next") UUID next, @Param("now") Instant now);

    /**
     * 新增实例指定级别的审批人覆盖记录。
     * 保存租户、实例、级别、新审批人及更新时间，供后续有效审批人查询优先使用。
     */
    void insertApproverOverride(@Param("tenantId") UUID tenantId, @Param("instanceId") UUID instanceId, @Param("levelNo") int levelNo, @Param("next") UUID next, @Param("now") Instant now);

    /**
     * 保存实例指定级别的审批人覆盖配置。
     * 先执行更新，未更新到记录时再插入；组合操作的事务及并发串行化由调用方负责。
     */
    default void overrideApprover(UUID tenantId, UUID instanceId, int levelNo, UUID next, Instant now) {
        if (updateApproverOverride(tenantId, instanceId, levelNo, next, now) == 0) {
    /**
     * 新增实例指定级别的审批人覆盖记录。
     * 保存租户、实例、级别、新审批人及更新时间，供后续有效审批人查询优先使用。
     */
            insertApproverOverride(tenantId, instanceId, levelNo, next, now);
        }
    }

}
