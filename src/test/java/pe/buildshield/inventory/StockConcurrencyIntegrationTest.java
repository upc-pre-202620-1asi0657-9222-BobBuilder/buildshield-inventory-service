package pe.buildshield.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.inventory.application.StockLevel;
import pe.buildshield.inventory.application.StockOperations;
import pe.buildshield.inventory.domain.model.ConcurrentStockModificationException;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InventoryIntegrationTest;
import pe.buildshield.inventory.support.TestDatabase;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QAS02 con PostgreSQL real (portado del Core): dos reservas simultáneas del mismo material cuando el stock no
 * alcanza para ambas. Solo una gana; la otra falla sin efectos y lo disponible nunca queda negativo. El consumo al
 * despachar nunca supera lo reservado y la liberación devuelve solo lo que quedó.
 */
@InventoryIntegrationTest
class StockConcurrencyIntegrationTest {

    private static final TenantInfo WAREHOUSE_MANAGER =
            new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "WAREHOUSE_MANAGER");

    @Autowired
    StockOperations operations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID warehouse;
    private UUID cement;

    @BeforeEach
    void stockOfTen() {
        TestDatabase.reset(jdbc);
        warehouse = UUID.randomUUID();
        cement = UUID.randomUUID();
        inTransaction(() -> operations.add(warehouse, cement, new BigDecimal("10"), "Guía"));
    }

    @RepeatedTest(value = 20, name = "repetición {currentRepetition} de {totalRepetitions}")
    void only_one_of_two_simultaneous_reservations_wins(RepetitionInfo repetition) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> reserve = () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            try {
                return inTransaction(() -> operations.reserve(warehouse, cement, new BigDecimal("7"), UUID.randomUUID(),
                        UUID.randomUUID()));
            } catch (InsufficientStockException | ConcurrentStockModificationException rejected) {
                return rejected;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(reserve);
            Future<Object> second = pool.submit(reserve);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(StockLevel.class::isInstance)
                    .as("repetición %d: exactamente una reserva gana", repetition.getCurrentRepetition()).hasSize(1);
            assertThat(outcomes).filteredOn(InsufficientStockException.class::isInstance).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(level("available_qty")).isEqualByComparingTo("3");
        assertThat(level("reserved_qty")).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservations", Long.class))
                .as("el perdedor no deja reserva").isEqualTo(1);
    }

    @Test
    void consumption_never_exceeds_what_was_reserved_and_release_returns_the_rest() {
        UUID line = UUID.randomUUID();
        inTransaction(() -> operations.reserve(warehouse, cement, new BigDecimal("6"), UUID.randomUUID(), line));
        inTransaction(() -> operations.consumeReservation(warehouse, cement, new BigDecimal("4"), line, "Despacho 1"));
        assertThatThrownBy(() -> inTransaction(() ->
                operations.consumeReservation(warehouse, cement, new BigDecimal("3"), line, "Despacho 2")))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", StockOperations.RESERVATION_NOT_AVAILABLE);

        assertThat(level("available_qty")).isEqualByComparingTo("4");
        assertThat(level("reserved_qty")).isEqualByComparingTo("2");

        TenantContext.runAs(WAREHOUSE_MANAGER, () -> inTransaction(() -> {
            var reservation = operationsReservation(line);
            return operations.release(reservation, "Cierre").orElseThrow();
        }));

        assertThat(level("available_qty")).isEqualByComparingTo("6");
        assertThat(level("reserved_qty")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_line_id = ?",
                String.class, line)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForList("SELECT type FROM stock_movements ORDER BY occurred_at, type", String.class))
                .containsExactlyInAnyOrder("ENTRY", "RESERVE", "DISPATCH", "RELEASE");
    }

    @Autowired
    pe.buildshield.inventory.domain.model.StockRepository repository;

    private pe.buildshield.inventory.domain.model.StockReservation operationsReservation(UUID line) {
        return repository.findReservation(line).orElseThrow();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return TenantContext.callAs(WAREHOUSE_MANAGER, () -> new TransactionTemplate(transactionManager)
                .execute(status -> work.get()));
    }

    private BigDecimal level(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM stock_items WHERE organization_id = ? "
                + "AND location_id = ? AND material_id = ?", BigDecimal.class, WAREHOUSE_MANAGER.organizationId(),
                warehouse, cement);
    }
}
