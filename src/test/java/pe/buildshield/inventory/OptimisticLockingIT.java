package pe.buildshield.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturnRepository;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockCountRepository;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InventoryIntegrationTest;
import pe.buildshield.inventory.support.TestDatabase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las columnas {@code version} de {@code balance_returns} y {@code stock_counts} con PostgreSQL real: quien guarda con
 * una versión vieja no tiene efecto (los servicios lo responden como 409 {@code RETURN_MODIFIED} y {@code COUNT_MODIFIED}).
 */
@InventoryIntegrationTest
class OptimisticLockingIT {

    private static final UUID USER = UUID.randomUUID();
    private static final TenantInfo TENANT = new TenantInfo(UUID.randomUUID(), USER, "ADMINISTRATOR");
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MaterialReturnRepository returns;

    @Autowired
    StockCountRepository counts;

    @BeforeEach
    void clean() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void a_stale_return_is_not_saved() {
        TenantContext.runAs(TENANT, () -> {
            UUID material = UUID.randomUUID();
            MaterialReturn created = MaterialReturn.register(UUID.randomUUID(), UUID.randomUUID(), List.of(
                    new MaterialReturn.DeclaredLine(material, BigDecimal.TEN, "Detalle")), "Nota", USER, NOW);
            returns.add(created);

            MaterialReturn first = returns.find(created.id()).orElseThrow();
            MaterialReturn stale = returns.find(created.id()).orElseThrow();
            first.request(USER, NOW);
            assertThat(returns.update(first)).isTrue();
            stale.cancel(USER, NOW);
            assertThat(returns.update(stale)).isFalse();

            MaterialReturn saved = returns.find(created.id()).orElseThrow();
            assertThat(saved.status()).isEqualTo(MaterialReturn.Status.REQUESTED);
            assertThat(saved.version()).isEqualTo(1);
            assertThat(saved.note()).isEqualTo("Nota");
            assertThat(saved.lines().get(0).detail()).isEqualTo("Detalle");
            assertThat(saved.requestedBy()).isEqualTo(USER);
            assertThat(saved.requestedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void a_stale_count_is_not_saved() {
        TenantContext.runAs(TENANT, () -> {
            StockCount created = StockCount.start(UUID.randomUUID(), List.of(UUID.randomUUID()), "Mensual", USER, NOW);
            counts.add(created);

            StockCount first = counts.find(created.id()).orElseThrow();
            StockCount stale = counts.find(created.id()).orElseThrow();
            first.cancel(USER, NOW);
            assertThat(counts.update(first)).isTrue();
            stale.cancel(USER, NOW);
            assertThat(counts.update(stale)).isFalse();

            StockCount saved = counts.find(created.id()).orElseThrow();
            assertThat(saved.status()).isEqualTo(StockCount.Status.CANCELLED);
            assertThat(saved.version()).isEqualTo(1);
        });
    }
}
