package com.flowdesk.ticket.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** SLA 调度与索引的启用开关、批次预算、轮询间隔、租约和资格有效期配置。 */
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

    /**
     * 返回功能开关，供组件按配置执行。
     */
    public boolean isEnabled() { return enabled; }
    /**
     * 设置功能开关，供配置绑定与启动校验使用。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * 返回Redis 索引开关，供组件按配置执行。
     */
    public boolean isRedisEnabled() { return redisEnabled; }
    /**
     * 设置Redis 索引开关，供配置绑定与启动校验使用。
     */
    public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }
    /**
     * 返回SLA 扫描器开关，供组件按配置执行。
     */
    public boolean isScannerEnabled() { return scannerEnabled; }
    /**
     * 设置SLA 扫描器开关，供配置绑定与启动校验使用。
     */
    public void setScannerEnabled(boolean scannerEnabled) { this.scannerEnabled = scannerEnabled; }
    /**
     * 返回索引重建器开关，供组件按配置执行。
     */
    public boolean isRebuilderEnabled() { return rebuilderEnabled; }
    /**
     * 设置索引重建器开关，供配置绑定与启动校验使用。
     */
    public void setRebuilderEnabled(boolean rebuilderEnabled) { this.rebuilderEnabled = rebuilderEnabled; }
    /**
     * 返回单批处理上限，供组件按配置执行。
     */
    public int getBatchSize() { return batchSize; }
    /**
     * 设置单批处理上限，供配置绑定与启动校验使用。
     */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    /**
     * 返回单批租户上限，供组件按配置执行。
     */
    public int getTenantBatchSize() { return tenantBatchSize; }
    /**
     * 设置单批租户上限，供配置绑定与启动校验使用。
     */
    public void setTenantBatchSize(int tenantBatchSize) { this.tenantBatchSize = tenantBatchSize; }
    /**
     * 返回轮询间隔，供组件按配置执行。
     */
    public Duration getPollInterval() { return pollInterval; }
    /**
     * 设置轮询间隔，供配置绑定与启动校验使用。
     */
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    /**
     * 返回领取租约时长，供组件按配置执行。
     */
    public Duration getLeaseDuration() { return leaseDuration; }
    /**
     * 设置领取租约时长，供配置绑定与启动校验使用。
     */
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    /** 保留 M8 环境变量名称对应的配置别名。 */
    public void setRebuildLeaseDuration(Duration value) { this.leaseDuration = value; }
    /**
     * 返回数据库安全扫描间隔，供组件按配置执行。
     */
    public Duration getSafetySweepInterval() { return safetySweepInterval; }
    /**
     * 设置数据库安全扫描间隔，供配置绑定与启动校验使用。
     */
    public void setSafetySweepInterval(Duration safetySweepInterval) { this.safetySweepInterval = safetySweepInterval; }
    /** 保留降级扫描环境变量名称对应的配置别名。 */
    public void setRedisDegradedSweepInterval(Duration value) { this.safetySweepInterval = value; }
    /**
     * 返回资格刷新间隔，供组件按配置执行。
     */
    public Duration getQualificationRefreshInterval() { return qualificationRefreshInterval; }
    /**
     * 设置资格刷新间隔，供配置绑定与启动校验使用。
     */
    public void setQualificationRefreshInterval(Duration value) { this.qualificationRefreshInterval = value; }
    /**
     * 返回资格最大有效时长，供组件按配置执行。
     */
    public Duration getQualificationMaxAge() { return qualificationMaxAge; }
    /**
     * 设置资格最大有效时长，供配置绑定与启动校验使用。
     */
    public void setQualificationMaxAge(Duration value) { this.qualificationMaxAge = value; }
    /**
     * 返回资格允许时钟偏差，供组件按配置执行。
     */
    public Duration getQualificationClockSkew() { return qualificationClockSkew; }
    /**
     * 设置资格允许时钟偏差，供配置绑定与启动校验使用。
     */
    public void setQualificationClockSkew(Duration value) { this.qualificationClockSkew = value; }

    /**
     * 校验SLA启用所需配置、数值范围及时间关系，无效配置拒绝启动。
     */
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
