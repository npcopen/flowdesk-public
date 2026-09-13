package com.flowdesk.common.web;

import java.time.Instant;

/** API-SPEC SCHEMA-051 service-local probe payload. */
public record ProbeStatus(Probe probe, Status status, Instant checkedAt) {

    public enum Probe {
        LIVENESS,
        READINESS
    }

    public enum Status {
        UP,
        DEGRADED
    }
}
