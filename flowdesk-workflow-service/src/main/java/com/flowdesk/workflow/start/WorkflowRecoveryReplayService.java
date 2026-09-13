package com.flowdesk.workflow.start;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.PermissionPolicy;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;

import java.util.UUID;

/** Authorized Workflow INBOX replay use case. */
public final class WorkflowRecoveryReplayService {

    private final WorkflowRecoveryReplayTransaction transaction;
    private final WorkflowRecoveryReplayWorker worker;

    WorkflowRecoveryReplayService(WorkflowRecoveryReplayTransaction transaction,
                                  WorkflowRecoveryReplayWorker worker) {
        this.transaction = transaction;
        this.worker = worker;
    }

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
        WorkflowRecoveryReplayTransaction.Acceptance acceptance = transaction.accept(
                context, recoveryId, human.userId(), acceptedReason,
                acceptedKey, expectedVersion);
        if (!acceptance.replayed() && acceptance.work() != null) {
            worker.submit(acceptance.work());
        }
        return new ReplayResult(acceptance.recovery(), acceptance.replayed());
    }

    public record ReplayResult(WorkflowRecoveryView recovery, boolean replayed) {
    }
}
