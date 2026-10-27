package com.flowdesk.iam.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地环境可选的服务 JWT 文件签发配置，默认关闭；映射格式为 tenantId:serviceId。
 * 默认签发一次后退出；常驻模式按刷新间隔轮换，且不记录令牌内容。
 */
@ConfigurationProperties(prefix = "flowdesk.iam.service-jwt-bootstrap")
public class ServiceJwtBootstrapProperties {

    private boolean enabled = false;
    private boolean exitAfterIssue = true;
    private Duration refreshInterval = Duration.ofMinutes(2);
    private String outputDirectory = ".data/service-tokens";
    private List<String> mappings = new ArrayList<>();

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
     * 返回签发 JWT 后退出应用的开关，供组件组装或运行时判断使用。
     */
    public boolean isExitAfterIssue() {
        return exitAfterIssue;
    }

    /**
     * 更新签发 JWT 后退出应用的开关，供 Spring 配置绑定及组件初始化使用。
     */
    public void setExitAfterIssue(boolean exitAfterIssue) {
        this.exitAfterIssue = exitAfterIssue;
    }

    /**
     * 返回服务 JWT 轮换间隔，供组件组装或运行时判断使用。
     */
    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * 设置服务 JWT 轮换周期并要求为正时长，启动签发后还会与令牌剩余有效期比较。
     */
    public void setRefreshInterval(Duration refreshInterval) {
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            throw new IllegalArgumentException("service-jwt-bootstrap refresh-interval must be positive");
        }
        this.refreshInterval = refreshInterval;
    }

    /**
     * 返回服务 JWT 文件输出目录，供组件组装或运行时判断使用。
     */
    public String getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * 更新服务 JWT 文件输出目录，供 Spring 配置绑定及组件初始化使用。
     */
    public void setOutputDirectory(String outputDirectory) {
        if (outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("service-jwt-bootstrap output-directory must not be blank");
        }
        this.outputDirectory = outputDirectory;
    }

    /**
     * 返回租户与服务身份映射列表，供组件组装或运行时判断使用。
     */
    public List<String> getMappings() {
        return mappings;
    }

    /**
     * 更新租户与服务身份映射列表，供 Spring 配置绑定及组件初始化使用。
     */
    public void setMappings(List<String> mappings) {
        this.mappings = mappings == null ? new ArrayList<>() : new ArrayList<>(mappings);
    }
}
