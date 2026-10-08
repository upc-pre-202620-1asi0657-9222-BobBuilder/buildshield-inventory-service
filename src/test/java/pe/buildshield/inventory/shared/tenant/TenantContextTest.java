package pe.buildshield.inventory.shared.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    private static final TenantInfo ALPHA = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "SUPERVISOR");
    private static final TenantInfo BETA = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "WAREHOUSE");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void require_fails_safely_when_there_is_no_context() {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void set_and_clear() {
        TenantContext.set(ALPHA);
        assertThat(TenantContext.require()).isEqualTo(ALPHA);

        TenantContext.clear();
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void run_as_restores_the_previous_context_even_on_failure() {
        TenantContext.set(ALPHA);

        assertThatThrownBy(() -> TenantContext.runAs(BETA, () -> {
            assertThat(TenantContext.require()).isEqualTo(BETA);
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(TenantContext.require()).isEqualTo(ALPHA);
        assertThat(TenantContext.isSystem()).isFalse();
    }

    @Test
    void system_mode_has_no_organization_and_is_restored() {
        TenantContext.set(ALPHA);

        boolean insideIsSystem = TenantContext.callAsSystem(() -> {
            assertThat(TenantContext.current()).isEmpty();
            return TenantContext.isSystem();
        });

        assertThat(insideIsSystem).isTrue();
        assertThat(TenantContext.isSystem()).isFalse();
        assertThat(TenantContext.require()).isEqualTo(ALPHA);
    }

    @Test
    void run_as_system_runs_the_task() {
        boolean[] ran = {false};
        TenantContext.runAsSystem(() -> ran[0] = TenantContext.isSystem());
        assertThat(ran[0]).isTrue();
    }

    @Test
    void context_is_per_thread() throws Exception {
        TenantContext.set(ALPHA);
        Object[] seen = new Object[1];

        Thread other = new Thread(() -> seen[0] = TenantContext.current());
        other.start();
        other.join();

        assertThat(seen[0]).isEqualTo(java.util.Optional.empty());
    }

    @Test
    void tenant_info_requires_all_fields() {
        assertThatThrownBy(() -> new TenantInfo(null, UUID.randomUUID(), "X")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TenantInfo(UUID.randomUUID(), null, "X")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
    }
}
