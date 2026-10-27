package com.flowdesk.workflow.service.start;
import com.flowdesk.workflow.start.AcceptedWorkflowStart;
import com.flowdesk.workflow.model.start.WorkflowStartModels.DuplicateCheck;
import com.flowdesk.workflow.model.start.WorkflowStartModels.VersionLookup;
/**
 * 启动只读边界，为消息去重及事务外版本预检提供稳定事实。
 */
public interface WorkflowStartQueryService {
    /**
     * 按声明的租户与业务条件查询消息重复事实，供调用方核对可见性、状态或幂等关系。
     */
    DuplicateCheck findDuplicate(AcceptedWorkflowStart message);
    /**
     * 按声明的租户与业务条件查询流程版本，供调用方核对可见性、状态或幂等关系。
     */
    VersionLookup findVersion(AcceptedWorkflowStart message);
}
