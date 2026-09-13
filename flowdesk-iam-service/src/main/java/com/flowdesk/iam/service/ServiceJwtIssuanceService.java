package com.flowdesk.iam.service;

import com.flowdesk.common.security.FlowDeskJwtProperties;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * IAM application service for issuing tenant-scoped service-identity JWTs.
 *
 * <p>The private signing key remains in IAM. Only the deployment-unit ids configured
 * in {@code flowdesk.security.jwt.allowed-service-ids} can be minted, which matches
 * the verifier allow-list used by the API-016 internal eligibility endpoint.</p>
 */
@Service
public class ServiceJwtIssuanceService {

    private final JwtIssuer jwtIssuer;
    private final List<String> allowedServiceIds;

    public ServiceJwtIssuanceService(JwtIssuer jwtIssuer, FlowDeskJwtProperties jwtProperties) {
        this.jwtIssuer = Objects.requireNonNull(jwtIssuer, "jwtIssuer");
        this.allowedServiceIds = List.copyOf(
                Objects.requireNonNull(jwtProperties, "jwtProperties").getAllowedServiceIds());
    }

    /**
     * Issues a short-lived RS256 service JWT ({@code aud=flowdesk-internal}).
     *
     * @param tenantId  tenant scope encoded in the token
     * @param serviceId deployment-unit id that will present the token
     * @return serialized JWT and its authoritative expiry
     * @throws IllegalArgumentException when the service id is not on the IAM allow-list
     */
    public IssuedJwt issue(UUID tenantId, String serviceId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (!allowedServiceIds.contains(serviceId)) {
            throw new IllegalArgumentException(
                    "serviceId is not in flowdesk.security.jwt.allowed-service-ids: " + serviceId);
        }
        return jwtIssuer.issueService(tenantId, serviceId);
    }
}
