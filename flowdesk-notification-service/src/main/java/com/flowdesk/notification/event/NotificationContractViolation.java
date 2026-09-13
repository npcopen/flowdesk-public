package com.flowdesk.notification.event;

import java.util.Optional;

/** A deterministic Notification delivery rejection with an explicit trust boundary. */
public class NotificationContractViolation extends RuntimeException {
    private final String code;
    private final TrustBoundary boundary;
    private final NotificationEnvelope envelope;

    public enum TrustBoundary {
        PRE_TRUST,
        TRUSTED_TENANT
    }

    public NotificationContractViolation(String code) {
        this(code, null, TrustBoundary.PRE_TRUST, null);
    }

    public NotificationContractViolation(String code, Throwable cause) {
        this(code, cause, TrustBoundary.PRE_TRUST, null);
    }

    private NotificationContractViolation(String code, Throwable cause,
                                          TrustBoundary boundary,
                                          NotificationEnvelope envelope) {
        super(code, cause);
        this.code = code;
        this.boundary = boundary;
        this.envelope = envelope;
    }

    public static NotificationContractViolation trusted(String code,
                                                        NotificationEnvelope envelope) {
        return new NotificationContractViolation(code, null,
                TrustBoundary.TRUSTED_TENANT, envelope);
    }

    public static NotificationContractViolation trusted(String code,
                                                        Throwable cause,
                                                        NotificationEnvelope envelope) {
        return new NotificationContractViolation(code, cause,
                TrustBoundary.TRUSTED_TENANT, envelope);
    }

    public String code() {
        return code;
    }

    public TrustBoundary boundary() {
        return boundary;
    }

    public boolean hasTrustedEnvelope() {
        return boundary == TrustBoundary.TRUSTED_TENANT && envelope != null;
    }

    public Optional<NotificationEnvelope> envelope() {
        return Optional.ofNullable(envelope);
    }
}
