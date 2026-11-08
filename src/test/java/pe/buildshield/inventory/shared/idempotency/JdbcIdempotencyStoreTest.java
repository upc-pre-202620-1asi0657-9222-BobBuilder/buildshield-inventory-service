package pe.buildshield.inventory.shared.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdempotencyStoreTest {

    @Test
    void lock_id_is_stable_and_depends_on_organization_and_key() {
        UUID organization = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isEqualTo(JdbcIdempotencyStore.lockId(organization, key));
        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isNotEqualTo(JdbcIdempotencyStore.lockId(UUID.randomUUID(), key));
        assertThat(JdbcIdempotencyStore.lockId(organization, key)).isNotEqualTo(JdbcIdempotencyStore.lockId(organization, UUID.randomUUID()));
    }

    @Test
    void record_matches_only_the_same_method_and_path() {
        IdempotencyRecord record = new IdempotencyRecord(UUID.randomUUID(), UUID.randomUUID(), "POST",
                "/api/v1/receptions", 201, "application/json", new byte[0], Instant.now());

        assertThat(record.isSameRequest("POST", "/api/v1/receptions")).isTrue();
        assertThat(record.isSameRequest("PUT", "/api/v1/receptions")).isFalse();
        assertThat(record.isSameRequest("POST", "/api/v1/orders")).isFalse();
    }
}
