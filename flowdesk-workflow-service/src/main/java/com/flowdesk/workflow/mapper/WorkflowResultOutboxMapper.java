package com.flowdesk.workflow.mapper;

import com.flowdesk.workflow.model.persistence.ApprovalFinalizedOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审批终结事件的本地发件箱写入接口。
 * 在调用方审批事务中保存事件和消息身份，使审批结果与待发布消息一起提交；消息投递由后续发布流程负责。
 */
@Mapper
public interface WorkflowResultOutboxMapper {
    /**
     * 保存审批终结事件及其本地发布意图。
     * event 提供租户、实例、消息身份、正文摘要及操作者，记录初始化为 PENDING，尝试次数和锁版本均为 0。
     */
    void insertApprovalFinalizedOutbox(@Param("event") ApprovalFinalizedOutbox event);
}
