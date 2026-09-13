package com.flowdesk.ticket.outbox;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.ticket.event.TicketEventContract;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded run of the Ticket workflow-start Outbox publication state machine.
 *
 * <p>Claim and completion are separate short local transactions. The synchronous Broker call is
 * deliberately outside both transactions. Scheduling and the concrete RocketMQ adapter remain
 * disabled until the later binding-activation step.</p>
 */
public final class TicketOutboxPublisher {

    static final String UNKNOWN_RESULT = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";

    private final TicketOutboxRepository repository;
    private final TicketOutboxTransport transport;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public TicketOutboxPublisher(TicketOutboxRepository repository,
                                 TicketOutboxTransport transport,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TicketOutboxPublisher(TicketOutboxRepository repository,
                                 TicketOutboxTransport transport,
                                 PlatformTransactionManager transactionManager) {
        this(repository, transport, transactionManager, Clock.systemUTC());
    }

    /** Scans at most 50 candidates and claims/sends each one immediately. */
    public PublishRunResult publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox publisher must not run inside a caller transaction");
        }
        if (TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Outbox publisher must start without a thread TenantContext");
        }

        Instant scanTime = clock.instant();
        List<TicketOutboxRepository.Candidate> candidates = repository.findCandidates(
                scanTime,
                scanTime.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE),
                TicketEventContract.WorkflowStartRequested.MAX_CLAIMS_PER_RUN);

        MutableRunResult result = new MutableRunResult(candidates.size());
        for (TicketOutboxRepository.Candidate candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(
                    candidate.tenantId(),
                    ActorRef.service(TicketEventContract.WorkflowStartRequested.PRODUCER_SERVICE),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount()
                        == TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS) {
                    recoverExpiredExhausted(candidate, result);
                }
                else {
                    claimSendAndComplete(candidate, result);
                }
            }
        }
        return result.freeze();
    }

    private void claimSendAndComplete(TicketOutboxRepository.Candidate candidate,
                                      MutableRunResult result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<TicketOutboxRepository.ClaimedOutbox> claimed = transactions.execute(status ->
                repository.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForSend++;

        TicketOutboxRepository.ClaimedOutbox row = claimed.get();
        TicketOutboxSendResult sendResult;
        try {
            TicketOutboxMessage message = WorkflowStartV1EnvelopeFactory.create(row);
            sendResult = transport.send(message);
            if (sendResult == null) {
                sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
            }
        }
        catch (WorkflowStartV1EnvelopeFactory.InvalidOutboxContractException invalid) {
            sendResult = TicketOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
        }
        catch (Exception unknown) {
            // Exception messages can contain credentials or endpoints and are never persisted here.
            sendResult = TicketOutboxSendResult.notConfirmed(UNKNOWN_RESULT, null);
        }

        TicketOutboxSendResult outcome = sendResult;
        Instant completedAt = clock.instant();
        Instant leaseCutoff = completedAt.minus(
                TicketEventContract.WorkflowStartRequested.CLAIM_LEASE);
        if (outcome.status() == TicketOutboxSendResult.Status.SEND_OK) {
            Boolean published = transactions.execute(status ->
                    repository.markPublished(row, completedAt, leaseCutoff));
            if (Boolean.TRUE.equals(published)) {
                result.published++;
            }
            else {
                result.lostCompletion++;
            }
            return;
        }

        String safeSummary = sanitizeSummary(outcome.safeErrorSummary());
        if (row.attemptCount()
                < TicketEventContract.WorkflowStartRequested.MAX_AUTOMATIC_SENDS) {
            Duration backoff = TicketEventContract.WorkflowStartRequested.FAILURE_BACKOFFS
                    .get(row.attemptCount() - 1);
            Boolean pending = transactions.execute(status -> repository.markPending(
                    row, completedAt, leaseCutoff, completedAt.plus(backoff),
                    outcome.errorCode()));
            if (Boolean.TRUE.equals(pending)) {
                result.rescheduled++;
            }
            else {
                result.lostCompletion++;
            }
        }
        else {
            Boolean recovered = transactions.execute(status -> repository.moveToRecovery(
                    row, completedAt, leaseCutoff, outcome.errorCode(), safeSummary,
                    UUID.randomUUID()));
            if (Boolean.TRUE.equals(recovered)) {
                result.recovered++;
            }
            else {
                result.lostCompletion++;
            }
        }
    }

    private void recoverExpiredExhausted(TicketOutboxRepository.Candidate candidate,
                                         MutableRunResult result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<TicketOutboxRepository.ClaimedOutbox> claimed = transactions.execute(status ->
                repository.claimForRecovery(candidate, token, claimedAt,
                        claimedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForRecovery++;
        Instant completedAt = clock.instant();
        Boolean recovered = transactions.execute(status -> repository.moveToRecovery(
                claimed.get(), completedAt,
                completedAt.minus(TicketEventContract.WorkflowStartRequested.CLAIM_LEASE),
                UNKNOWN_RESULT, null, UUID.randomUUID()));
        if (Boolean.TRUE.equals(recovered)) {
            result.recovered++;
        }
        else {
            result.lostCompletion++;
        }
    }

    private static String sanitizeSummary(String summary) {
        if (summary == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(summary.length(), 1000));
        boolean previousSpace = false;
        for (int i = 0; i < summary.length() && sanitized.length() < 1000; i++) {
            char value = summary.charAt(i);
            if (Character.isISOControl(value) || Character.isWhitespace(value)) {
                if (!previousSpace && sanitized.length() > 0) {
                    sanitized.append(' ');
                    previousSpace = true;
                }
            }
            else {
                sanitized.append(value);
                previousSpace = false;
            }
        }
        String value = sanitized.toString().trim();
        return value.isEmpty() ? null : value;
    }

    public record PublishRunResult(int scanned, int claimedForSend, int claimedForRecovery,
                                   int published, int rescheduled, int recovered,
                                   int lostClaim, int lostCompletion) {
    }

    private static final class MutableRunResult {
        private final int scanned;
        private int claimedForSend;
        private int claimedForRecovery;
        private int published;
        private int rescheduled;
        private int recovered;
        private int lostClaim;
        private int lostCompletion;

        private MutableRunResult(int scanned) {
            this.scanned = scanned;
        }

        private PublishRunResult freeze() {
            return new PublishRunResult(scanned, claimedForSend, claimedForRecovery,
                    published, rescheduled, recovered, lostClaim, lostCompletion);
        }
    }
}
