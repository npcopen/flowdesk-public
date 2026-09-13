package com.flowdesk.workflow.outbox;

import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import com.flowdesk.workflow.event.WorkflowReverseEventContract;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded publisher shared by the two independent M6 reverse contracts. */
public final class WorkflowOutboxPublisher {
    static final String RESULT_UNKNOWN = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";

    private final WorkflowOutboxRepository repository;
    private final WorkflowOutboxTransport transport;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WorkflowOutboxPublisher(WorkflowOutboxRepository repository,
                                   WorkflowOutboxTransport transport,
                                   PlatformTransactionManager transactionManager) {
        this(repository, transport, transactionManager, Clock.systemUTC());
    }

    WorkflowOutboxPublisher(WorkflowOutboxRepository repository,
                            WorkflowOutboxTransport transport,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.repository = repository;
        this.transport = transport;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PublishResult publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Workflow Outbox poll must start outside context/tx");
        }
        Instant now = clock.instant();
        List<WorkflowOutboxRepository.Candidate> candidates = repository.findCandidates(
                now, now.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE),
                WorkflowReverseEventContract.OUTBOX_MAX_CLAIMS);
        Mutable result = new Mutable(candidates.size());
        for (WorkflowOutboxRepository.Candidate candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(candidate.tenantId(),
                    ActorRef.service(WorkflowReverseEventContract.PRODUCER_SERVICE),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount()
                        == WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
                    recoverExpired(candidate, result);
                }
                else {
                    send(candidate, result);
                }
            }
        }
        return result.freeze();
    }

    private void send(WorkflowOutboxRepository.Candidate candidate, Mutable result) {
        Instant claimedAt = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<WorkflowOutboxRepository.Claimed> claimed = transactions.execute(tx ->
                repository.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForSend++;
        WorkflowOutboxRepository.Claimed row = claimed.get();
        WorkflowOutboxSendResult outcome;
        try {
            outcome = transport.send(WorkflowOutboxEnvelopeFactory.create(row));
            if (outcome == null) {
                outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
            }
        }
        catch (WorkflowOutboxEnvelopeFactory.InvalidContract invalid) {
            outcome = WorkflowOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (Exception unknown) {
            outcome = WorkflowOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }

        Instant completed = clock.instant();
        Instant cutoff = completed.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE);
        if (outcome.status() == WorkflowOutboxSendResult.Status.SEND_OK) {
            Boolean done = transactions.execute(tx -> repository.markPublished(row, completed, cutoff));
            if (Boolean.TRUE.equals(done)) result.published++; else result.lostCompletion++;
            return;
        }
        if (row.attemptCount() < WorkflowReverseEventContract.OUTBOX_MAX_ATTEMPTS) {
            Instant next = completed.plus(WorkflowReverseEventContract.FAILURE_BACKOFFS
                    .get(row.attemptCount() - 1));
            WorkflowOutboxSendResult failed = outcome;
            Boolean done = transactions.execute(tx -> repository.markPending(
                    row, completed, cutoff, next, failed.errorCode()));
            if (Boolean.TRUE.equals(done)) result.rescheduled++; else result.lostCompletion++;
        }
        else {
            WorkflowOutboxSendResult failed = outcome;
            Boolean done = transactions.execute(tx -> repository.moveToRecovery(
                    row, completed, cutoff, failed.errorCode(),
                    sanitize(failed.safeSummary()), UUID.randomUUID()));
            if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
        }
    }

    private void recoverExpired(WorkflowOutboxRepository.Candidate candidate, Mutable result) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        Optional<WorkflowOutboxRepository.Claimed> claimed = transactions.execute(tx ->
                repository.claimForRecovery(candidate, token, now,
                        now.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE)));
        if (claimed == null || claimed.isEmpty()) {
            result.lostClaim++;
            return;
        }
        result.claimedForRecovery++;
        Instant completed = clock.instant();
        Boolean done = transactions.execute(tx -> repository.moveToRecovery(
                claimed.get(), completed,
                completed.minus(WorkflowReverseEventContract.OUTBOX_CLAIM_LEASE),
                RESULT_UNKNOWN, null, UUID.randomUUID()));
        if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.substring(0, Math.min(1000, cleaned.length()));
    }

    public record PublishResult(int scanned, int claimedForSend, int claimedForRecovery,
                                int published, int rescheduled, int recovered,
                                int lostClaim, int lostCompletion) { }

    private static final class Mutable {
        private final int scanned;
        private int claimedForSend, claimedForRecovery, published, rescheduled, recovered,
                lostClaim, lostCompletion;
        private Mutable(int scanned) { this.scanned = scanned; }
        private PublishResult freeze() {
            return new PublishResult(scanned, claimedForSend, claimedForRecovery, published,
                    rescheduled, recovered, lostClaim, lostCompletion);
        }
    }
}
