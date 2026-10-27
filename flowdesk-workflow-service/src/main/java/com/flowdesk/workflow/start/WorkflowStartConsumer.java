package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.start.*;
import com.flowdesk.workflow.model.start.WorkflowStartModels;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.UUID;

/**
 * 五阶段工作流启动消费入口：信封校验、租户建立、去重、版本及 IAM 预检、本地事务提交。
 * Broker 适配器只有收到 ACK 结果后才可确认消息。
 */
public final class WorkflowStartConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartConsumer.class);
    private static final String DB_UNAVAILABLE = "WORKFLOW_DB_UNAVAILABLE";
    private static final String IAM_UNAVAILABLE = "IAM_ELIGIBILITY_UNAVAILABLE";

    private final WorkflowStartEnvelopeGuard guard;
    private final WorkflowStartQueryService queries;
    private final WorkflowStartCommandService transaction;
    private final WorkflowApproverEligibilityChecker eligibilityChecker;

    /**
     * 绑定严格信封守卫、只读预检、事务写入及审批资格检查器，将租户可信校验、事务外依赖调用和原子提交分阶段执行。
     */
    WorkflowStartConsumer(WorkflowStartEnvelopeGuard guard,
                          WorkflowStartQueryService queries,
                          WorkflowStartCommandService transaction,
                          WorkflowApproverEligibilityChecker eligibilityChecker) {
        this.guard = guard;
        this.queries = queries;
        this.transaction = transaction;
        this.eligibilityChecker = eligibilityChecker;
    }

    /**
     * 先严格校验信封再建立可信租户，协调事务外预检及原子启动，暂时性故障返回代理重试。
     */
    public WorkflowStartConsumeResult consume(WorkflowStartTransportMessage delivery) {
        final AcceptedWorkflowStart message;
        try {
            // 1. 先校验传输契约；通过之前不允许访问任何租户业务数据。
            message = guard.validateMain(delivery);
        }
        catch (WorkflowStartContractViolation deterministicPoison) {
            LOG.warn("Workflow-start delivery ACKed by transport guard; code={}",
                    deterministicPoison.code());
            return WorkflowStartConsumeResult.ack(deterministicPoison.code(), null);
        }

        // 2. 只使用已验证的信封事实建立可信租户上下文。
        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                return consumeInsideTenant(message);
            }
            catch (WorkflowIamUnavailableException unavailable) {
                LOG.warn("Workflow-start delivery returned to Broker; dependency=IAM");
                return WorkflowStartConsumeResult.retry(IAM_UNAVAILABLE);
            }
            catch (DataAccessException | TransactionException unavailable) {
                LOG.warn("Workflow-start delivery returned to Broker; dependency=DATABASE");
                return WorkflowStartConsumeResult.retry(DB_UNAVAILABLE);
            }
        }
    }

    /**
     * 在可信租户内检查重复、已发布版本与 IAM 资格，再由写服务提交启动事实。
     */
    private WorkflowStartConsumeResult consumeInsideTenant(AcceptedWorkflowStart message) {
        // 3. 先按消息标识、再按业务意图只读去重，顺序与固定契约保持一致。
        WorkflowStartModels.DuplicateCheck fast = queries.findDuplicate(message);
        if (fast.terminalDuplicate()) {
            return WorkflowStartConsumeResult.ack(
                    "WORKFLOW_START_DUPLICATE", fast.existing().resultId());
        }
        if (fast.kind() == WorkflowStartModels.DuplicateKind.CONFLICT) {
            WorkflowStartModels.Completion settled =
                    transaction.complete(message, WorkflowStartModels.conflictOnly());
            if (settled.kind() == WorkflowStartModels.CompletionKind.ACK) {
                return WorkflowStartConsumeResult.ack(
                        settled.code(), settled.workflowInstanceId());
            }
            // 运行账户不能改写既有 Inbox；此分支仅防御快速读取与加锁复核之间的外部删除。
        }

        // 4. 检查流程版本；仅需创建首级任务时才在事务外预检审批人资格。
        WorkflowStartModels.VersionLookup version = queries.findVersion(message);
        WorkflowStartModels.Preflight preflight;
        if (!version.valid()) {
            preflight = WorkflowStartModels.rejected(null, version.errorCode());
        }
        else if (version.version().requiresApproval(message)) {
            UUID approver = version.version().level1ApproverUserId();
            if (!eligibilityChecker.isEligibleApprover(message.tenantId(), approver)) {
                // 审批人资格不满足属于可恢复的外部资格失败，交回 Broker 消耗有限重试预算。
                // 在独立死信接收器接管前，不提交 Inbox、实例、任务或 Outbox 事实。
                return WorkflowStartConsumeResult.retry(
                        WorkflowStartModels.approverNotEligibleCode());
            }
            else {
                preflight = WorkflowStartModels.ready(version.version(), approver);
            }
        }
        else {
            preflight = WorkflowStartModels.ready(version.version(), null);
        }

        // 5. 在本地事务内加锁并重新裁决，原子提交 Inbox、业务副作用及审计后才允许 ACK。
        WorkflowStartModels.Completion completion = transaction.complete(message, preflight);
        if (completion.kind() != WorkflowStartModels.CompletionKind.ACK) {
            throw new IllegalStateException("Workflow-start preflight unexpectedly disappeared");
        }
        return WorkflowStartConsumeResult.ack(
                completion.code(), completion.workflowInstanceId());
    }
}
