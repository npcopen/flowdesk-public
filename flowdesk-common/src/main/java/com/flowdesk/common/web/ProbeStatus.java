package com.flowdesk.common.web;

import java.time.Instant;

/**
 * 服务本地健康探针的响应数据，对应 API-SPEC SCHEMA-051。
 */
public record ProbeStatus(Probe probe, Status status, Instant checkedAt) {

    /**
     * 健康探针类型，区分进程存活与是否可以接收流量。
     */
    public enum Probe {
        LIVENESS,
        READINESS
    }

    /**
     * 健康探针对外状态，由探针按检查结果填充。
     */
    public enum Status {
        UP,
        DEGRADED
    }
}
