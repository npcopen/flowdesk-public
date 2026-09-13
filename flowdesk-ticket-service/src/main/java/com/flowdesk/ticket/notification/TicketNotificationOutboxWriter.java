package com.flowdesk.ticket.notification;

import com.flowdesk.ticket.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Writes one immutable Ticket -> Notification intent inside the caller transaction. */
@Component
public final class TicketNotificationOutboxWriter {
    private final TicketNotificationOutboxRepository repository;
    private final TicketRepository legacyRepository;

    /** Compatibility constructor for narrow unit fixtures that use TicketRepository directly. */
    public TicketNotificationOutboxWriter(TicketRepository repository) {
        this.repository = null;
        this.legacyRepository = repository;
    }

    public TicketNotificationOutboxWriter(TicketNotificationOutboxRepository repository) {
        this.repository = repository;
        this.legacyRepository = null;
    }

    @Autowired
    public TicketNotificationOutboxWriter(TicketNotificationOutboxRepository repository,
                                          TicketRepository ignored) {
        this.repository = repository;
        this.legacyRepository = null;
    }

    public void write(UUID tenantId, TicketNotificationIntent.Intent intent) {
        if (repository != null) {
            repository.insertIntent(tenantId, intent);
            return;
        }
        legacyRepository.insertOutboxEvent(tenantId, intent.outboxId(), intent.messageId(),
                intent.publicationKey(), intent.businessIntentKey(), "NOTIFICATION_REQUEST",
                intent.sourceRefId(), TicketNotificationIntent.BINDING.contractRef(), 1,
                intent.bodyBytes(), intent.bodySha256(), intent.correlationId(),
                intent.causationId(), intent.occurredAt());
    }
}
