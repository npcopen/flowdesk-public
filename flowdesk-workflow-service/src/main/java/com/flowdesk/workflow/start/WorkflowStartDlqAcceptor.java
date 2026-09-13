package com.flowdesk.workflow.start;

import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** Independent acceptor for the frozen main-consumer DLQ. */
public final class WorkflowStartDlqAcceptor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStartDlqAcceptor.class);
    private static final String DB_UNAVAILABLE = "WORKFLOW_DB_UNAVAILABLE";

    private final WorkflowStartEnvelopeGuard guard;
    private final WorkflowStartDlqTransaction transaction;

    WorkflowStartDlqAcceptor(WorkflowStartEnvelopeGuard guard,
                             WorkflowStartDlqTransaction transaction) {
        this.guard = guard;
        this.transaction = transaction;
    }

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
