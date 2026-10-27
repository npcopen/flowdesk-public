package com.flowdesk.workflow.service.recovery;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.ReplayWork;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.ReplaySource;
import com.flowdesk.workflow.start.WorkflowStartConsumeResult;
import java.util.Optional;
/**
 * 恢复执行结算边界，读取和更新受持久化重放身份约束，阻止旧工作者覆盖新租约。
 */
public interface WorkflowRecoveryExecutionService {
 /**
  * 读取本次租约内的原始来源，失去租约或来源已终态时不再执行消息。
  */
 Optional<ReplaySource> currentSource(ReplayWork work);
 /**
  * 按已提交工作项和消费结果结算恢复，只允许本次持有者修改相关状态。
  */
 void completed(ReplayWork work, WorkflowStartConsumeResult result);
 /**
  * 保存本次仍持有的重放失败结果，过期持有者不能重新开放新租约。
  */
 void failed(ReplayWork work, String errorCode);
}
