package com.flowdesk.workflow.start;

import com.flowdesk.common.recovery.RecoveryReplayMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作流服务的持久化收件箱重放 Mapper 入口。
 * 继承公共 RecoveryReplayMapper 的到期重放扫描和租约领取 SQL，使其绑定工作流数据源并被 MyBatis 扫描。
 */
@Mapper
public interface WorkflowRecoveryReplayMapper extends RecoveryReplayMapper {
}
