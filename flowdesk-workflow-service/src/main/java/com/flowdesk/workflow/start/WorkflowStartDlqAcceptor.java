package com.flowdesk.workflow.start;

import com.flowdesk.workflow.service.start.WorkflowStartDlqService;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * 主消费组死信队列的独立接收入口，重新校验信封后交给死信受理事务。
 */
public final class WorkflowStartDlqAcceptor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartDlqAcceptor.class);
    private static final String DB_UNAVAILABLE = "WORKFLOW_DB_UNAVAILABLE";

    private final WorkflowStartEnvelopeGuard guard;
    private final WorkflowStartDlqService transaction;

    /**
     * 注入信封守卫与死信接纳服务，先验证原消息身份再在可信租户中提交恢复事实。
     */
    WorkflowStartDlqAcceptor(WorkflowStartEnvelopeGuard guard,
                             WorkflowStartDlqService transaction) {
        this.guard = guard;
        this.transaction = transaction;
    }

    /**
     * 按死信绑定校验原消息并建立租户上下文，接纳事务成功后才确认死信。
     */
    public WorkflowStartConsumeResult accept(WorkflowStartTransportMessage delivery) {
        final AcceptedWorkflowStart message;
        try {
            message = guard.validateDlq(delivery);
        }
        catch (WorkflowStartContractViolation deterministicPoison) {
            LOG.warn("Workflow-start DLQ delivery ACKed before tenant context; code={}",
                    deterministicPoison.code());
            return WorkflowStartConsumeResult.ack(deterministicPoison.code(), null);
        }

        TenantContext context = TenantContext.ofValidatedBrokerMessage(
                message.tenantId(), message.producerService(), message.correlationId());
        try (TenantContextScope ignored = TenantContextScope.open(context)) {
            try {
                return transaction.accept(message);
            }
            catch (DataAccessException | TransactionException transientFailure) {
                LOG.warn("Workflow-start DLQ delivery returned to acceptor Broker group; dependency=DATABASE");
                return WorkflowStartConsumeResult.retry(DB_UNAVAILABLE);
            }
        }
    }
}
