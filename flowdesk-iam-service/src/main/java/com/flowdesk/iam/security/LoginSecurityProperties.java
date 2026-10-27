package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 登录失败次数阈值与临时锁定时长配置，供登录限制作统一判断。
 */
@ConfigurationProperties(prefix = "flowdesk.security.login")
public class LoginSecurityProperties {

    /**
     * 触发账户临时锁定前允许的密码失败次数。
     */
    private int maxFailedAttempts = 5;

    /**
     * 失败次数达到阈值后的临时锁定时长。
     */
    private Duration lockoutDuration = Duration.ofMinutes(30);

    /**
     * 返回触发临时锁定的连续登录失败阈值，供业务校验或组件初始化读取。
     */
    public int maxFailedAttempts() {
        return maxFailedAttempts;
    }

    /**
     * 设置连续错误密码阈值，要求为正整数，避免无效配置关闭或反转锁定保护。
     */
    public void setMaxFailedAttempts(int maxFailedAttempts) {
        if (maxFailedAttempts <= 0) {
            throw new IllegalArgumentException("flowdesk.security.login.max-failed-attempts must be positive");
        }
        this.maxFailedAttempts = maxFailedAttempts;
    }

    /**
     * 返回账户临时锁定时长，供业务校验或组件初始化读取。
     */
    public Duration lockoutDuration() {
        return lockoutDuration;
    }

    /**
     * 设置账户锁定时长，拒绝 null、零和负时长，使失败阈值后的锁定期限始终有效。
     */
    public void setLockoutDuration(Duration lockoutDuration) {
        if (lockoutDuration == null || lockoutDuration.isZero() || lockoutDuration.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.login.lockout-duration must be positive");
        }
        this.lockoutDuration = lockoutDuration;
    }
}
