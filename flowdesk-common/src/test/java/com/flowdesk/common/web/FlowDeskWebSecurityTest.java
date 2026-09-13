package com.flowdesk.common.web;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.authz.RoleCode;
import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.common.security.JwtIssuer;
import com.flowdesk.common.security.JwtVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlowDeskWebSecurityTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static JwtIssuer issuer;
    private static MockMvc mockMvc;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        issuer = new JwtIssuer(keyPair.getPrivate(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 900, 300);
        JwtVerifier verifier = new JwtVerifier(keyPair.getPublic(), "flowdesk-iam-service",
                "flowdesk-api", "flowdesk-internal", 30);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new PermissionInterceptor())
                .addFilters(new CorrelationIdFilter(List.of()),
                        new FlowDeskRequestBodyPolicyFilter(new tools.jackson.databind.ObjectMapper()),
                        new HumanJwtAuthenticationFilter(verifier, List.of(), List.of()),
                        new ServiceJwtAuthenticationFilter(verifier, List.of(), List.of()))
                .build();
    }

    @AfterEach
    void clearHolders() {
        JwtPrincipalHolder.clear();
    }

    @Test
    void validTokenWithPermissionPassesAndCleansContext() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_READ));
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer " + jwt.token()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.ok").value(true));
        org.assertj.core.api.Assertions.assertThat(JwtPrincipalHolder.currentOrNull()).isNull();
    }

    @Test
    void validTokenWithoutPermissionIsDenied() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.REQUESTER), Set.of());
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer " + jwt.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void forgedTenantHeadersAreIgnoredAndNotTrusted() throws Exception {
        IssuedJwt jwt = issuer.issueHuman(TENANT, USER,
                Set.of(RoleCode.TENANT_ADMIN), Set.of(PermissionCode.IAM_USER_READ));
        // The JWT tenant is the only trust root; a forged header must not change anything.
        mockMvc.perform(get("/api/v1/protected")
                        .header("Authorization", "Bearer " + jwt.token())
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void oversizedBodyIsRejectedWithRequestTooLarge() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .contentType("application/json")
                        .content("x".repeat(257 * 1024)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void loginBodyHasItsOwnSmallerLimit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("x".repeat(9 * 1024)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void nonIdentityContentEncodingIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/protected")
                        .header("Content-Encoding", "gzip")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("CONTENT_ENCODING_UNSUPPORTED"));
    }

    @Test
    void invalidSignatureIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/protected").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_FAILED"));
    }

    @RestController
    static class ProtectedController {

        @GetMapping("/api/v1/protected")
        @RequiresPermission(PermissionCode.IAM_USER_READ)
        public java.util.Map<String, Boolean> get() {
            return java.util.Map.of("ok", true);
        }
    }
}
