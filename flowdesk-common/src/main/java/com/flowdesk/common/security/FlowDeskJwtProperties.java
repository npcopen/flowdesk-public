package com.flowdesk.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT trust-boundary configuration shared by all deployment units.
 *
 * <p>IAM configures the private signing key; Gateway and the four fact services
 * configure only the public verification key. There is intentionally no default key:
 * missing material fails fast at startup.</p>
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

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getPublicKeyLocation() {
        return publicKeyLocation;
    }

    public void setPublicKeyLocation(String publicKeyLocation) {
        this.publicKeyLocation = publicKeyLocation;
    }

    public String getPrivateKeyLocation() {
        return privateKeyLocation;
    }

    public void setPrivateKeyLocation(String privateKeyLocation) {
        this.privateKeyLocation = privateKeyLocation;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getHumanAudience() {
        return humanAudience;
    }

    public void setHumanAudience(String humanAudience) {
        this.humanAudience = humanAudience;
    }

    public String getServiceAudience() {
        return serviceAudience;
    }

    public void setServiceAudience(String serviceAudience) {
        this.serviceAudience = serviceAudience;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.access-token-ttl must be positive");
        }
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getServiceTokenTtl() {
        return serviceTokenTtl;
    }

    public void setServiceTokenTtl(Duration serviceTokenTtl) {
        if (serviceTokenTtl == null || serviceTokenTtl.isZero() || serviceTokenTtl.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.service-token-ttl must be positive");
        }
        this.serviceTokenTtl = serviceTokenTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        if (clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("flowdesk.security.jwt.clock-skew must not be negative");
        }
        this.clockSkew = clockSkew;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    public List<String> getServiceJwtPaths() {
        return serviceJwtPaths;
    }

    public void setServiceJwtPaths(List<String> serviceJwtPaths) {
        this.serviceJwtPaths = serviceJwtPaths;
    }

    public List<String> getAllowedServiceIds() {
        return allowedServiceIds;
    }

    public void setAllowedServiceIds(List<String> allowedServiceIds) {
        this.allowedServiceIds = allowedServiceIds;
    }

    public List<String> getStrictCorrelationPaths() {
        return strictCorrelationPaths;
    }

    public void setStrictCorrelationPaths(List<String> strictCorrelationPaths) {
        this.strictCorrelationPaths = strictCorrelationPaths;
    }
}
