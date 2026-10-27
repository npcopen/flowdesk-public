package com.flowdesk.alert.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定 flowdesk.alert.webhook 下的入口开关、请求时间窗口和运行时密钥文件路径。
 *
 * <p>认证过滤器按请求时间与服务器时间的绝对差判断是否过期，接纳事务也使用同一窗口设置 nonce 保留时间。
 * 开关启用后由密钥仓在创建时执行完整校验；默认零窗口和空路径不能构成可用的认证配置。
 */
@ConfigurationProperties(prefix = "flowdesk.alert.webhook")
public class WebhookProperties {
    private boolean enabled;
    private Duration timestampWindow = Duration.ZERO;
    private String secretsFile = "";

    /**
     * 返回组件启用开关，供组件组装或运行时判断使用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 更新组件启用开关，供 Spring 配置绑定及组件初始化使用。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回Webhook 允许的请求时间偏差窗口，供组件组装或运行时判断使用。
     */
    public Duration getTimestampWindow() {
        return timestampWindow;
    }

    /**
     * 更新Webhook 允许的请求时间偏差窗口，供 Spring 配置绑定及组件初始化使用。
     */
    public void setTimestampWindow(Duration timestampWindow) {
        this.timestampWindow = timestampWindow;
    }

    /**
     * 返回Webhook 集成签名密钥文件路径，供组件组装或运行时判断使用。
     */
    public String getSecretsFile() {
        return secretsFile;
    }

    /**
     * 更新Webhook 集成签名密钥文件路径，供 Spring 配置绑定及组件初始化使用。
     */
    public void setSecretsFile(String secretsFile) {
        this.secretsFile = secretsFile;
    }

    /** 启用入口时要求正数且不超过一小时的窗口和非空密钥路径；不完整的配置直接阻止启动。 */
    void validateEnabled() {
        if (!enabled
                || timestampWindow == null
                || timestampWindow.isZero()
                || timestampWindow.isNegative()
                || timestampWindow.compareTo(Duration.ofHours(1)) > 0
                || secretsFile == null
                || secretsFile.isBlank()) {
            throw new IllegalStateException("Alert Webhook security configuration is incomplete");
        }
    }
}
