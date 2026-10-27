package com.flowdesk.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 各服务共用的 JWT 信任边界配置。
 * IAM 配置签名私钥，Gateway 与业务服务仅配置验签公钥；不提供默认密钥，缺少密钥时启动失败。
 */
@ConfigurationProperties(prefix = "flowdesk.security.jwt")
public class FlowDeskJwtProperties {

    private String publicKeyPem = "";
    private String privateKeyPem = "";
    private String publicKeyLocation = "";
    private String privateKeyLocation = "";
    private String issuer = "flowdesk-iam-service";
    private String humanAudience = "flowdesk-api";
    private String serviceAudience = "flowdesk-internal";
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration serviceTokenTtl = Duration.ofMinutes(5);
    private Duration clockSkew = Duration.ofSeconds(30);
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/api/v1/auth/login",
            "/api/v1/internal/health/**",
            "/api/v1/webhooks/**",
            "/error"
    ));
    private List<String> serviceJwtPaths = new ArrayList<>();
    private List<String> allowedServiceIds = new ArrayList<>();
    private List<String> strictCorrelationPaths = new ArrayList<>(List.of("/api/v1/internal/**"));

    /**
     * 返回内联 RSA 公钥 PEM，供配置绑定和运行时组件初始化使用。
     */
    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    /**
     * 设置内联 RSA 公钥 PEM，供配置绑定和运行时组件初始化使用。
     */
    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    /**
     * 返回内联 RSA 私钥 PEM，供配置绑定和运行时组件初始化使用。
     */
    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    /**
     * 设置内联 RSA 私钥 PEM，供配置绑定和运行时组件初始化使用。
     */
    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    /**
     * 返回RSA 公钥资源位置，供配置绑定和运行时组件初始化使用。
     */
    public String getPublicKeyLocation() {
        return publicKeyLocation;
    }

    /**
     * 设置RSA 公钥资源位置，供配置绑定和运行时组件初始化使用。
     */
    public void setPublicKeyLocation(String publicKeyLocation) {
        this.publicKeyLocation = publicKeyLocation;
    }

    /**
     * 返回RSA 私钥资源位置，供配置绑定和运行时组件初始化使用。
     */
    public String getPrivateKeyLocation() {
        return privateKeyLocation;
    }

    /**
     * 设置RSA 私钥资源位置，供配置绑定和运行时组件初始化使用。
     */
    public void setPrivateKeyLocation(String privateKeyLocation) {
        this.privateKeyLocation = privateKeyLocation;
    }

    /**
     * 返回JWT 签发方，供配置绑定和运行时组件初始化使用。
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * 设置JWT 签发方，供配置绑定和运行时组件初始化使用。
     */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /**
     * 返回人类 JWT 专用受众，供配置绑定和运行时组件初始化使用。
     */
    public String getHumanAudience() {
        return humanAudience;
    }

    /**
     * 设置人类 JWT 专用受众，供配置绑定和运行时组件初始化使用。
     */
    public void setHumanAudience(String humanAudience) {
        this.humanAudience = humanAudience;
    }

    /**
     * 返回服务 JWT 专用受众，供配置绑定和运行时组件初始化使用。
     */
    public String getServiceAudience() {
        return serviceAudience;
    }

    /**
     * 设置服务 JWT 专用受众，供配置绑定和运行时组件初始化使用。
     */
    public void setServiceAudience(String serviceAudience) {
        this.serviceAudience = serviceAudience;
    }

    /**
     * 返回人类令牌有效时长，供配置绑定和运行时组件初始化使用。
     */
    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /**
     * 设置人类令牌有效时长，供配置绑定和运行时组件初始化使用。
     */
    public void setAccessTokenTtl(Duration accessTokenTtl) {
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.access-token-ttl must be positive");
        }
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * 返回服务令牌有效时长，供配置绑定和运行时组件初始化使用。
     */
    public Duration getServiceTokenTtl() {
        return serviceTokenTtl;
    }

    /**
     * 设置服务令牌有效时长，供配置绑定和运行时组件初始化使用。
     */
    public void setServiceTokenTtl(Duration serviceTokenTtl) {
        if (serviceTokenTtl == null || serviceTokenTtl.isZero() || serviceTokenTtl.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.service-token-ttl must be positive");
        }
        this.serviceTokenTtl = serviceTokenTtl;
    }

    /**
     * 返回时间验证允许的时钟偏差，供配置绑定和运行时组件初始化使用。
     */
    public Duration getClockSkew() {
        return clockSkew;
    }

    /**
     * 设置时间验证允许的时钟偏差，供配置绑定和运行时组件初始化使用。
     */
    public void setClockSkew(Duration clockSkew) {
        if (clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.clock-skew must not be negative");
        }
        this.clockSkew = clockSkew;
    }

    /**
     * 返回无需人类 JWT 的公开路径，供配置绑定和运行时组件初始化使用。
     */
    public List<String> getPublicPaths() {
        return publicPaths;
    }

    /**
     * 设置无需人类 JWT 的公开路径，供配置绑定和运行时组件初始化使用。
     */
    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    /**
     * 返回服务 JWT 内部路径，供配置绑定和运行时组件初始化使用。
     */
    public List<String> getServiceJwtPaths() {
        return serviceJwtPaths;
    }

    /**
     * 设置服务 JWT 内部路径，供配置绑定和运行时组件初始化使用。
     */
    public void setServiceJwtPaths(List<String> serviceJwtPaths) {
        this.serviceJwtPaths = serviceJwtPaths;
    }

    /**
     * 返回允许调用的服务标识白名单，供配置绑定和运行时组件初始化使用。
     */
    public List<String> getAllowedServiceIds() {
        return allowedServiceIds;
    }

    /**
     * 设置允许调用的服务标识白名单，供配置绑定和运行时组件初始化使用。
     */
    public void setAllowedServiceIds(List<String> allowedServiceIds) {
        this.allowedServiceIds = allowedServiceIds;
    }

    /**
     * 返回严格关联标识路径，供配置绑定和运行时组件初始化使用。
     */
    public List<String> getStrictCorrelationPaths() {
        return strictCorrelationPaths;
    }

    /**
     * 设置严格关联标识路径，供配置绑定和运行时组件初始化使用。
     */
    public void setStrictCorrelationPaths(List<String> strictCorrelationPaths) {
        this.strictCorrelationPaths = strictCorrelationPaths;
    }
}
