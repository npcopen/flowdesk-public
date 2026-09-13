package com.flowdesk.alert.webhook;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Pre-tenant candidate lookup. A selector match is deliberately not a trusted context. */
public final class WebhookIntegrationRepository {
    private final JdbcTemplate jdbc;
    public WebhookIntegrationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Candidate> findCandidates(byte[] selectorHash) {
        return jdbc.query("""
                SELECT tenant_id,integration_id,integration_code,integration_name,status,
                       api_key_verifier,api_key_verifier_scheme,signing_scheme_version,
                       valid_from,valid_until
                FROM monitoring_integration
                WHERE api_key_selector_hash=?
                ORDER BY tenant_id,integration_id
                """, CANDIDATE_MAPPER, selectorHash);
    }

    public record Candidate(UUID tenantId, UUID integrationId, String integrationCode,
                            String integrationName, String status, byte[] apiKeyVerifier,
                            String verifierScheme, int signingVersion,
                            Instant validFrom, Instant validUntil) {
        public Candidate { apiKeyVerifier = apiKeyVerifier.clone(); }
        @Override public byte[] apiKeyVerifier() { return apiKeyVerifier.clone(); }
    }

    private static final org.springframework.jdbc.core.RowMapper<Candidate> CANDIDATE_MAPPER =
            (rs, n) -> new Candidate(uuid(rs.getBytes("tenant_id")),
                    uuid(rs.getBytes("integration_id")), rs.getString("integration_code"),
                    rs.getString("integration_name"), rs.getString("status"),
                    rs.getBytes("api_key_verifier"), rs.getString("api_key_verifier_scheme"),
                    rs.getInt("signing_scheme_version"), instant(rs, "valid_from"),
                    instant(rs, "valid_until"));
    private static UUID uuid(byte[] value) {
        ByteBuffer b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong());
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant();
    }
}
