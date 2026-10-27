package com.flowdesk.workflow.service.recovery.impl;

import com.flowdesk.workflow.start.*;
import com.flowdesk.workflow.model.recovery.WorkflowRecoveryModels.*;
import com.flowdesk.workflow.service.recovery.*;
import com.flowdesk.workflow.dto.*;
import java.util.UUID;
import com.flowdesk.workflow.dto.WorkflowRecoverySupport;
import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;


/**
 * 人工重放的授权入口：校验操作者与请求条件，先持久化受理结果，再提交可执行的 Inbox 重放任务。
 */
public final class WorkflowRecoveryReplayServiceImpl implements WorkflowRecoveryReplayService {

    private final WorkflowRecoveryAcceptanceService transaction;
    private final WorkflowRecoveryReplayWorker worker;

    /**
     * 绑定事务接纳服务与后台工作者，先持久化人工请求，再提交可执行来源避免异步处理先于事务提交。
     */
    public WorkflowRecoveryReplayServiceImpl(WorkflowRecoveryAcceptanceService transaction,
                                  WorkflowRecoveryReplayWorker worker) {
        this.transaction = transaction;
        this.worker = worker;
    }

    /**
     * 验证恢复输入并提交接纳事务，Inbox 重放交由后台执行，返回当前恢复视图。
     */
    public ReplayResult replay(UUID recoveryId,
                               String reason,
                               String idempotencyKey,
                               String ifMatch) {
        PermissionPolicy.requireCurrentPrincipal(PermissionCode.RECOVERY_REPLAY_TENANT);
        TenantContext context = TenantContextHolder.current();
        if (!(context.actor() instanceof ActorRef.Human human)) {
            throw new IllegalStateException("Workflow recovery replay requires human JWT context");
        }
        String acceptedReason = WorkflowRecoverySupport.requireReason(reason);
        String acceptedKey = WorkflowRecoverySupport.requireIdempotencyKey(idempotencyKey);
        long expectedVersion = WorkflowRecoverySupport.parseIfMatch(ifMatch);
        Acceptance acceptance = transaction.accept(
                context, recoveryId, human.userId(), acceptedReason,
                acceptedKey, expectedVersion);
        // 受理事务已提交后才调度执行；请求重试仅返回既有受理结果，避免重复提交后台任务。
        if (!acceptance.replayed() && acceptance.work() != null) {
            worker.submit(acceptance.work());
        }
        return new ReplayResult(acceptance.recovery(), acceptance.replayed());
    }

}
