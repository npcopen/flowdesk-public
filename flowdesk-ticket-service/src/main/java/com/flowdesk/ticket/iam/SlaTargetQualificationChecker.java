package com.flowdesk.ticket.iam;

import java.time.Instant;
import java.util.UUID;

/** Port for the concrete ServiceDeskAdmin API-016 qualification check. */
public interface SlaTargetQualificationChecker {

    Qualification checkServiceDeskAdmin(UUID tenantId, UUID userId);

    record Qualification(UUID subjectUserId, boolean eligible, String reasonCode,
                         long qualificationVersion, Instant checkedAt) {
        public Qualification {
            if (subjectUserId == null || reasonCode == null || reasonCode.isBlank()
                    || qualificationVersion <= 0 || checkedAt == null) {
                throw new IllegalArgumentException("Invalid IAM qualification result");
            }
        }
    }
}
