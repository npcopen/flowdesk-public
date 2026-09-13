package com.flowdesk.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"flowdesk.gateway.webhook-require-tls=true","spring.cloud.service-registry.auto-registration.enabled=false"})
class GatewayWebhookTlsIntegrationTest{
    @LocalServerPort int port;private WebTestClient client;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("flowdesk.security.jwt.public-key-pem",()->resource("test-jwt-public-key.pem"));}
    @BeforeEach void client(){client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();}
    @Test void forgedForwardedProtoCannotSubstituteForAnActualTlsSession(){client.post().uri("/api/v1/webhooks/monitoring/alerts").header("X-Forwarded-Proto","https").header("X-FlowDesk-Api-Key","fdak1.invalid.invalid").header("X-FlowDesk-Signature-Version","1").header("X-FlowDesk-Timestamp","1").header("X-FlowDesk-Nonce","nonce_00000000000").header("X-FlowDesk-Signature","sha256="+"0".repeat(64)).header("Content-Type","application/json; charset=utf-8").bodyValue("{}").exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.error.code").isEqualTo("WEBHOOK_AUTHENTICATION_FAILED");}
    private static String resource(String name){try(var in=GatewayWebhookTlsIntegrationTest.class.getClassLoader().getResourceAsStream(name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
