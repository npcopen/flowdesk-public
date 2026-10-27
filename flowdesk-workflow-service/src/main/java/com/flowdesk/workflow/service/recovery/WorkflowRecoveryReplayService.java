package com.flowdesk.workflow.service.recovery;

import com.flowdesk.workflow.dto.WorkflowRecoveryView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 人工恢复用例入口，协调请求校验、持久化接纳与异步重放提交。
 */
public interface WorkflowRecoveryReplayService {
    /**
     * 按操作原因、幂等键和资源版本接纳重放，返回可追溯恢复记录及执行结果。
     */
    ReplayResult replay(UUID recoveryId, String reason, String idempotencyKey, String ifMatch);
    /**
     * 人工恢复用例结果，组合当前恢复视图与幂等重放标记。
     */
    record ReplayResult(WorkflowRecoveryView recovery, boolean replayed) {}
}
