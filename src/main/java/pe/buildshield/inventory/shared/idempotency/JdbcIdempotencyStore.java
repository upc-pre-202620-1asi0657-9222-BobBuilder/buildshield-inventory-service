package pe.buildshield.inventory.shared.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** El advisory lock dura la transacción; la identidad de una operación nunca se elimina. */
public class JdbcIdempotencyStore implements IdempotencyStore {
    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, row) -> new IdempotencyRecord(
            rs.getObject("organization_id", UUID.class), rs.getObject("idempotency_key", UUID.class),
            rs.getString("request_method"), rs.getString("request_path"), rs.getInt("response_status"),
            rs.getString("response_content_type"), rs.getBytes("response_body"),
            rs.getTimestamp("created_at").toInstant(), rs.getObject("user_id", UUID.class),
            rs.getString("actor_role"), rs.getString("request_fingerprint"),
            rs.getString("response_location"), rs.getBoolean("response_expired"));
    private final JdbcTemplate jdbc;
    public JdbcIdempotencyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean tryLock(UUID organizationId, UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                lockId(organizationId, key)));
    }
    @Override public Optional<IdempotencyRecord> find(UUID organizationId, UUID key) {
        return jdbc.query("SELECT * FROM idempotency_keys WHERE organization_id = ? AND idempotency_key = ?",
                ROW_MAPPER, organizationId, key).stream().findFirst();
    }
    @Override public void save(IdempotencyRecord r) {
        jdbc.update("""
                INSERT INTO idempotency_keys (organization_id, idempotency_key, request_method, request_path,
                    response_status, response_content_type, response_body, created_at, user_id, actor_role,
                    request_fingerprint, response_location, response_expired)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, r.organizationId(), r.key(), r.requestMethod(), r.requestPath(), r.responseStatus(),
                r.responseContentType(), r.responseBody(), Timestamp.from(r.createdAt()), r.userId(), r.role(),
                r.requestFingerprint(), r.responseLocation(), r.responseExpired());
    }
    /** Retira solo las respuestas; conserva las marcas que impiden duplicaciones tardías. */
    @Override public int retireResponsesCreatedBefore(Instant threshold) {
        return jdbc.update("""
                UPDATE idempotency_keys SET response_body = NULL, response_content_type = NULL,
                    response_location = NULL, response_expired = TRUE
                WHERE created_at < ? AND response_expired = FALSE
                """, Timestamp.from(threshold));
    }
    static long lockId(UUID organizationId, UUID key) {
        long hash = 1125899906842597L;
        for (long part : new long[] {organizationId.getMostSignificantBits(), organizationId.getLeastSignificantBits(),
                key.getMostSignificantBits(), key.getLeastSignificantBits()}) hash = 31 * hash + part;
        return hash;
    }
}
