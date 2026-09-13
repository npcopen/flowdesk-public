package com.flowdesk.ticket.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Opt-in switches and bounded budgets for the SLA scheduler/index. */
@ConfigurationProperties(prefix = "flowdesk.ticket.sla")
public class TicketSlaProperties {
    private boolean enabled;
    private boolean redisEnabled;
    private boolean scannerEnabled;
    private boolean rebuilderEnabled;
    private int batchSize = 50;
    private int tenantBatchSize = 100;
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration safetySweepInterval = Duration.ofSeconds(5);
    private Duration qualificationRefreshInterval = Duration.ofMinutes(5);
    private Duration qualificationMaxAge = Duration.ofMinutes(5);
    private Duration qualificationClockSkew = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRedisEnabled() { return redisEnabled; }
    public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }
    public boolean isScannerEnabled() { return scannerEnabled; }
    public void setScannerEnabled(boolean scannerEnabled) { this.scannerEnabled = scannerEnabled; }
    public boolean isRebuilderEnabled() { return rebuilderEnabled; }
    public void setRebuilderEnabled(boolean rebuilderEnabled) { this.rebuilderEnabled = rebuilderEnabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getTenantBatchSize() { return tenantBatchSize; }
    public void setTenantBatchSize(int tenantBatchSize) { this.tenantBatchSize = tenantBatchSize; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    /** Configuration alias retained for the M8 environment variable name. */
    public void setRebuildLeaseDuration(Duration value) { this.leaseDuration = value; }
    public Duration getSafetySweepInterval() { return safetySweepInterval; }
    public void setSafetySweepInterval(Duration safetySweepInterval) { this.safetySweepInterval = safetySweepInterval; }
    /** Configuration alias retained for the degraded-sweep environment name. */
    public void setRedisDegradedSweepInterval(Duration value) { this.safetySweepInterval = value; }
    public Duration getQualificationRefreshInterval() { return qualificationRefreshInterval; }
    public void setQualificationRefreshInterval(Duration value) { this.qualificationRefreshInterval = value; }
    public Duration getQualificationMaxAge() { return qualificationMaxAge; }
    public void setQualificationMaxAge(Duration value) { this.qualificationMaxAge = value; }
    public Duration getQualificationClockSkew() { return qualificationClockSkew; }
    public void setQualificationClockSkew(Duration value) { this.qualificationClockSkew = value; }

    void validate() {
        if (batchSize < 1 || batchSize > 200) throw new IllegalArgumentException("SLA batchSize must be 1..200");
        if (tenantBatchSize < 1 || tenantBatchSize > 1000) throw new IllegalArgumentException("SLA tenantBatchSize must be 1..1000");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) throw new IllegalArgumentException("SLA pollInterval must be positive");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("SLA leaseDuration must be positive");
        if (safetySweepInterval == null || safetySweepInterval.isNegative() || safetySweepInterval.isZero()) throw new IllegalArgumentException("SLA safetySweepInterval must be positive");
        if (qualificationRefreshInterval == null || qualificationRefreshInterval.isNegative() || qualificationRefreshInterval.isZero()) throw new IllegalArgumentException("SLA qualificationRefreshInterval must be positive");
        if (qualificationMaxAge == null || qualificationMaxAge.isNegative() || qualificationMaxAge.isZero()) throw new IllegalArgumentException("SLA qualificationMaxAge must be positive");
        if (qualificationClockSkew == null || qualificationClockSkew.isNegative()) throw new IllegalArgumentException("SLA qualificationClockSkew must not be negative");
    }
}
