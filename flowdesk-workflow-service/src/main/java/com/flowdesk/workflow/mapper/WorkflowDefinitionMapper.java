package com.flowdesk.workflow.mapper;

import com.flowdesk.workflow.model.persistence.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流定义、可编辑草稿和已发布版本的持久化接口。
 * 查询按租户隔离，草稿迁移通过锁版本控制并发，并提供发布事务所需的行锁及版本号查询。
 */
@Mapper
public interface WorkflowDefinitionMapper {
    /**
     * 创建处于 EDITABLE、UNVALIDATED 状态的双级审批草稿。
     * 保存条件表达式及两级审批人，初始化锁版本为 0，并记录人工操作者和时间。
     */
    void insertDraft(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId, @Param("definitionId") UUID definitionId, @Param("fieldCode") String fieldCode, @Param("operator") String operator, @Param("valueType") String valueType, @Param("value") String value, @Param("level1ApproverUserId") UUID level1ApproverUserId, @Param("level2ApproverUserId") UUID level2ApproverUserId, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 修改可编辑草稿的条件和两级审批人。
     * 仅在 expectedVersion 匹配时更新，并清除校验摘要、校验时间，使草稿重新进入 UNVALIDATED 状态。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int updateDraft(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId, @Param("expectedVersion") long expectedVersion, @Param("fieldCode") String fieldCode, @Param("operator") String operator, @Param("valueType") String valueType, @Param("value") String value, @Param("level1ApproverUserId") UUID level1ApproverUserId, @Param("level2ApproverUserId") UUID level2ApproverUserId, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 将可编辑草稿标记为校验通过并保存摘要。
     * 通过 expectedVersion 校验并发修改，以 digest 和校验时间固化本次校验对应的内容。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int markDraftValidated(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId, @Param("expectedVersion") long expectedVersion, @Param("digest") byte[] digest, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 清除草稿校验结果并保留未校验状态。
     * 仅更新租户内 EDITABLE 且锁版本匹配的草稿，清空校验摘要和时间，同时递增锁版本。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int keepDraftUnvalidated(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId, @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 将已校验通过的可编辑草稿标记为已消费。
     * 仅允许 EDITABLE、VALID 且 expectedVersion 匹配的记录转为 CONSUMED，供版本发布事务使用。
     *
     * @return 实际受影响的行数；为 0 表示没有记录满足本次写入条件
     */
    int consumeDraft(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId, @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 将草稿内容保存为已发布的工作流版本快照。
     * 从 draft 复制条件、两级审批人及校验摘要，使用指定版本标识与 versionNo，并保留来源草稿。
     */
    void insertVersion(@Param("tenantId") UUID tenantId, @Param("workflowVersionId") UUID workflowVersionId, @Param("draft") DraftRow draft, @Param("versionNo") int versionNo, @Param("actorId") UUID actorId, @Param("now") Instant now);

    /**
     * 按租户以游标分页查询工作流定义。
     * 按定义编码或名称与定义标识稳定排序，ascending 控制方向；最多返回 pageSize + 1 条以判断下一页。
     */
    List<DefinitionRow> findDefinitions(@Param("tenantId") UUID tenantId, @Param("sortColumn") String sortColumn, @Param("ascending") boolean ascending, @Param("cursorValue") String cursorValue, @Param("cursorId") UUID cursorId, @Param("pageSize") int pageSize);

    /**
     * 按租户和定义标识查询工作流定义。
     * 返回定义编码、名称、状态和锁版本；不存在时返回空 Optional。
     */
    Optional<DefinitionRow> findDefinition(@Param("tenantId") UUID tenantId, @Param("definitionId") UUID definitionId);

    /**
     * 读取指定工作流定义并申请数据库行锁。
     * 使用租户和 definitionId 定位并执行 FOR UPDATE，应在调用方事务中使用以串行化后续定义级操作。
     */
    Optional<DefinitionRow> findDefinitionForUpdate(@Param("tenantId") UUID tenantId, @Param("definitionId") UUID definitionId);

    /**
     * 按租户和草稿标识读取草稿快照。
     * 包含条件、审批人、校验状态及锁版本；不存在时返回空 Optional。
     */
    Optional<DraftRow> findDraft(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId);

    /**
     * 读取租户内指定草稿并申请数据库行锁。
     * FOR UPDATE 保护事务中的草稿状态判断和后续修改；不存在时返回空 Optional。
     */
    Optional<DraftRow> findDraftForUpdate(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId);

    /**
     * 读取指定工作流定义当前可编辑的草稿。
     * 按租户、definitionId 及 EDITABLE 状态筛选，不包含已消费的历史草稿。
     */
    Optional<DraftRow> findCurrentDraft(@Param("tenantId") UUID tenantId, @Param("definitionId") UUID definitionId);

    /**
     * 计算指定定义的下一个发布版本号。
     * 返回现有最大版本号加一，无历史版本时返回 1；查询本身不锁行，并发发布需由调用方事务协调。
     */
    int nextVersionNo(@Param("tenantId") UUID tenantId, @Param("definitionId") UUID definitionId);

    /**
     * 按租户和版本标识读取已保存的工作流版本。
     * 返回发布时的条件、审批人及快照摘要；不存在时返回空 Optional。
     */
    Optional<VersionRow> findVersion(@Param("tenantId") UUID tenantId, @Param("workflowVersionId") UUID workflowVersionId);

    /**
     * 查找由指定草稿发布的工作流版本。
     * 按租户和 source_draft_id 定位，用于核对同一草稿的发布结果及幂等处理。
     */
    Optional<VersionRow> findVersionBySourceDraft(@Param("tenantId") UUID tenantId, @Param("draftId") UUID draftId);

    /**
     * 分页读取租户内指定定义的版本列表。
     * 可按版本号筛选，按版本号或发布时间与版本标识进行游标排序；最多返回 pageSize + 1 条。
     */
    List<VersionRow> findVersions(@Param("tenantId") UUID tenantId, @Param("definitionId") UUID definitionId, @Param("versionNo") Integer versionNo, @Param("sort") String sort, @Param("cursorValue") String cursorValue, @Param("cursorId") UUID cursorId, @Param("pageSize") int pageSize);
}
