package com.flowdesk.workflow.notification;

import com.flowdesk.common.notification.NotificationEventContract;
import com.flowdesk.common.tenant.ActorRef;
import com.flowdesk.common.tenant.TenantContext;
import com.flowdesk.common.tenant.TenantContextHolder;
import com.flowdesk.common.tenant.TenantContextScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded at-least-once publisher for Workflow Notification requests. */
public final class WorkflowNotificationOutboxPublisher {
    static final String RESULT_UNKNOWN = "BROKER_RESULT_UNKNOWN";
    static final String INVALID_CONTRACT = "OUTBOX_CONTRACT_INVALID";
    private final WorkflowNotificationOutboxRepository repository;
    private final WorkflowNotificationOutboxTransport transport;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WorkflowNotificationOutboxPublisher(WorkflowNotificationOutboxRepository repository,
                                               WorkflowNotificationOutboxTransport transport,
                                               PlatformTransactionManager manager, Clock clock) {
        this.repository = Objects.requireNonNull(repository); this.transport = Objects.requireNonNull(transport);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager)); this.clock = Objects.requireNonNull(clock);
    }
    public WorkflowNotificationOutboxPublisher(WorkflowNotificationOutboxRepository repository,
                                               WorkflowNotificationOutboxTransport transport,
                                               PlatformTransactionManager manager) {
        this(repository, transport, manager, Clock.systemUTC());
    }

    public PublishResult publishOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TenantContextHolder.currentOrNull() != null) {
            throw new IllegalStateException("Workflow Notification publisher must start outside context/transaction");
        }
        Instant now = instant();
        List<WorkflowNotificationOutboxRepository.Candidate> candidates = repository.findCandidates(
                now, now.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE),
                NotificationEventContract.OUTBOX_MAX_CLAIMS);
        Mutable result = new Mutable(candidates.size());
        for (var candidate : candidates) {
            TenantContext context = TenantContext.ofPersistedTask(candidate.tenantId(),
                    ActorRef.service(NotificationEventContract.WORKFLOW.producerService()),
                    candidate.correlationId());
            try (TenantContextScope ignored = TenantContextScope.open(context)) {
                if (candidate.attemptCount() >= NotificationEventContract.OUTBOX_MAX_ATTEMPTS) {
                    recover(candidate, result);
                } else send(candidate, result);
            }
        }
        return result.freeze();
    }

    private void send(WorkflowNotificationOutboxRepository.Candidate candidate, Mutable result) {
        Instant claimedAt = instant(); String token = UUID.randomUUID().toString();
        Optional<WorkflowNotificationOutboxRepository.Claimed> claim = transactions.execute(tx ->
                repository.claimForSend(candidate, token, claimedAt,
                        claimedAt.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE)));
        if (claim == null || claim.isEmpty()) { result.lostClaim++; return; }
        result.claimedForSend++;
        var row = claim.get(); WorkflowNotificationOutboxSendResult outcome;
        try {
            outcome = transport.send(WorkflowNotificationOutboxEnvelopeFactory.create(row));
            if (outcome == null) outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (WorkflowNotificationOutboxEnvelopeFactory.InvalidContract invalid) {
            WorkflowNotificationOutboxSendResult failed = WorkflowNotificationOutboxSendResult.notConfirmed(INVALID_CONTRACT, null);
            Boolean recovered = transactions.execute(tx -> repository.rejectContract(row, instant(),
                    instant().minus(NotificationEventContract.OUTBOX_CLAIM_LEASE), failed.errorCode(),
                    "Persisted Notification contract failed validation",
                    WorkflowNotificationOutboxRepository.recoveryId(row)));
            if (Boolean.TRUE.equals(recovered)) result.recovered++; else result.lostCompletion++;
            return;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null);
        }
        catch (Exception unknown) { outcome = WorkflowNotificationOutboxSendResult.notConfirmed(RESULT_UNKNOWN, null); }
        Instant completed = instant(); Instant cutoff = completed.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE);
        if (outcome.status() == WorkflowNotificationOutboxSendResult.Status.SEND_OK) {
            Boolean done = transactions.execute(tx -> repository.markPublished(row, completed, cutoff));
            if (Boolean.TRUE.equals(done)) result.published++; else result.lostCompletion++;
        } else if (row.attemptCount() < NotificationEventContract.OUTBOX_MAX_ATTEMPTS) {
            Duration backoff = NotificationEventContract.FAILURE_BACKOFFS.get(row.attemptCount() - 1);
            WorkflowNotificationOutboxSendResult failed = outcome;
            Boolean done = transactions.execute(tx -> repository.markPending(row, completed, cutoff,
                    completed.plus(backoff), failed.errorCode()));
            if (Boolean.TRUE.equals(done)) result.rescheduled++; else result.lostCompletion++;
        } else {
            WorkflowNotificationOutboxSendResult failed = outcome;
            Boolean done = transactions.execute(tx -> repository.moveToRecovery(row, completed, cutoff,
                    failed.errorCode(), sanitize(failed.safeErrorSummary()),
                    WorkflowNotificationOutboxRepository.recoveryId(row)));
            if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
        }
    }

    private void recover(WorkflowNotificationOutboxRepository.Candidate candidate, Mutable result) {
        Instant claimedAt = instant(); String token = UUID.randomUUID().toString();
        Optional<WorkflowNotificationOutboxRepository.Claimed> claim = transactions.execute(tx ->
                repository.claimForRecovery(candidate, token, claimedAt,
                        claimedAt.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE)));
        if (claim == null || claim.isEmpty()) { result.lostClaim++; return; }
        result.claimedForRecovery++;
        Instant completed = instant();
        Boolean done = transactions.execute(tx -> repository.moveToRecovery(claim.get(), completed,
                completed.minus(NotificationEventContract.OUTBOX_CLAIM_LEASE), RESULT_UNKNOWN, null,
                WorkflowNotificationOutboxRepository.recoveryId(claim.get())));
        if (Boolean.TRUE.equals(done)) result.recovered++; else result.lostCompletion++;
    }

    private Instant instant() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static String sanitize(String value) {
        if (value == null) return null; String clean = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return clean.isEmpty() ? null : clean.substring(0, Math.min(1000, clean.length()));
    }

    public record PublishResult(int scanned, int claimedForSend, int claimedForRecovery,
                                int published, int rescheduled, int recovered,
                                int lostClaim, int lostCompletion) { }
    private static final class Mutable {
        private final int scanned; private int claimedForSend, claimedForRecovery, published,
                rescheduled, recovered, lostClaim, lostCompletion;
        Mutable(int scanned) { this.scanned = scanned; }
        PublishResult freeze() { return new PublishResult(scanned, claimedForSend, claimedForRecovery,
                published, rescheduled, recovered, lostClaim, lostCompletion); }
    }
}
