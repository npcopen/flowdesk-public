package com.flowdesk.workflow.service;

import com.flowdesk.workflow.dto.WorkflowDtos;
import java.util.List;
import java.util.UUID;

/**
 * 工作流业务用例契约，供入口通过稳定方法调用查询或命令处理，并统一约束可见范围及副作用。
 */
public interface WorkflowDefinitionService {
    /**
     * 分页查询当前租户可见的流程定义，按排序及游标生成稳定结果。
     */
    WorkflowPage<WorkflowDtos.WorkflowDefinitionResponse> listDefinitions(
            int pageSize, String pageToken, String sort);

    /**
     * 读取当前租户的流程定义详情；实现先核验 WORKFLOW_READ_TENANT 权限，定义不存在或不可见时返回统一的 404 资源错误。
     */
    WorkflowDtos.WorkflowDefinitionResponse getDefinition(UUID definitionId);

    /**
     * 按声明的租户与业务条件查询当前草稿，供调用方核对可见性、状态或幂等关系。
     */
    WorkflowDtos.WorkflowDraftResponse getCurrentDraft(UUID definitionId);

    /**
     * 按声明的租户与业务条件查询草稿，供调用方核对可见性、状态或幂等关系。
     */
    WorkflowDtos.WorkflowDraftResponse getDraft(UUID draftId);

    /**
     * 基于定义创建受版本与幂等保护的草稿，重复请求复用原草稿结果。
     */
    DraftCommandResult createDraft(UUID definitionId,
                                          WorkflowDtos.WorkflowDraftWriteRequest request,
                                          String idempotencyKey, String ifMatch);

    /**
     * 按客户端版本保存草稿修改，编辑后原规则校验结果不再作为发布依据。
     */
    WorkflowDtos.WorkflowDraftResponse updateDraft(
            UUID draftId, WorkflowDtos.WorkflowDraftWriteRequest request, String ifMatch);

    /**
     * 校验指定版本草稿的受限规则和审批配置，返回与本次草稿内容对应的校验结果。
     */
    WorkflowDtos.WorkflowValidationResponse validateDraft(UUID draftId, String ifMatch);

    /**
     * 发布已校验草稿为不可变版本，使用幂等键避免重复请求创建多个版本。
     */
    VersionCommandResult publishDraft(UUID draftId, String ifMatch,
                                             String idempotencyKey);

    /**
     * 分页查询当前租户可见的流程版本，按排序及游标生成稳定结果。
     */
    WorkflowPage<WorkflowDtos.WorkflowVersionResponse> listVersions(
            UUID definitionId, Integer versionNo, int pageSize,
            String pageToken, String sort);

    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    WorkflowDtos.WorkflowVersionResponse getVersion(UUID workflowVersionId);

    /**
     * 工作流处理结果封装，组合业务快照及分页或幂等元信息供调用入口使用。
     */
    public record DraftCommandResult(WorkflowDtos.WorkflowDraftResponse draft,
                                     boolean replayed) {
    }

    /**
     * 工作流处理结果封装，组合业务快照及分页或幂等元信息供调用入口使用。
     */
    public record VersionCommandResult(WorkflowDtos.WorkflowVersionResponse version,
                                       boolean replayed) {
    }
}
