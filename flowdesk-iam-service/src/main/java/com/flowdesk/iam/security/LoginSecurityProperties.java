package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login throttling policy (API-SPEC API-001 lockout). The exact threshold and duration
 * remain SECURITY-controlled; these are the operational defaults until SECURITY freezes
 * them.
 */
@ConfigurationProperties(prefix = "flowdesk.security.login")
public class LoginSecurityProperties {

    /** Failed attempts before the account becomes temporarily locked. */
    private int maxFailedAttempts = 5;

    /** How long the account stays locked once the threshold is reached. */
    private Duration lockoutDuration = Duration.ofMinutes(30);

    public int maxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        if (maxFailedAttempts <= 0) {
            throw new IllegalArgumentException("flowdesk.security.login.max-failed-attempts must be positive");
        }
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration lockoutDuration() {
        return lockoutDuration;
    }

    public void setLockoutDuration(Duration lockoutDuration) {
        if (lockoutDuration == null || lockoutDuration.isZero() || lockoutDuration.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.login.lockout-duration must be positive");
        }
        this.lockoutDuration = lockoutDuration;
    }
}
