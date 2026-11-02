package pe.buildshield.inventory.domain.model;

import org.junit.jupiter.api.Test;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo de vida del conteo de validación (US41): contar, comparar, revisar, ajustar o anular. */
class StockCountTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID ROSA = UUID.randomUUID();
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID STEEL = UUID.randomUUID();

    private StockCount started() {
        return StockCount.start(WAREHOUSE, List.of(CEMENT, STEEL, CEMENT), "  Conteo mensual  ", ROSA, NOW);
    }

    @Test
    void a_count_starts_in_progress_with_one_line_per_material() {
        StockCount count = started();

        assertThat(count.status()).isEqualTo(StockCount.Status.IN_PROGRESS);
        assertThat(count.note()).isEqualTo("Conteo mensual");
        assertThat(count.lines()).extracting(StockCountLine::materialId).containsExactly(CEMENT, STEEL);
        assertThat(count.lines()).allMatch(line -> line.countedQty() == null && line.difference() == null);
        assertThat(count.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(count.startedBy()).isEqualTo(ROSA);
        assertThat(count.startedAt()).isEqualTo(NOW);
        assertThat(count.version()).isZero();
        assertThat(count.differences()).isEmpty();
    }

    @Test
    void a_count_needs_materials_and_short_notes() {
        assertThatThrownBy(() -> StockCount.start(WAREHOUSE, List.of(), null, ROSA, NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "COUNT_WITHOUT_MATERIALS");
        assertThatThrownBy(() -> StockCount.start(WAREHOUSE, List.of(CEMENT), "x".repeat(201), ROSA, NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "TEXT_TOO_LONG");
        assertThat(StockCount.start(WAREHOUSE, List.of(CEMENT), "   ", ROSA, NOW).note()).isNull();
        assertThatThrownBy(() -> new StockCount(UUID.randomUUID(), WAREHOUSE, StockCount.Status.IN_PROGRESS, null,
                ROSA, NOW, List.of(), 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finishing_compares_the_counted_quantities_with_the_system() {
        StockCount count = started();
        count.registerCounts(Map.of(CEMENT, new BigDecimal("445"), STEEL, new BigDecimal("20")));
        count.registerCounts(Map.of(STEEL, new BigDecimal("20.5")));

        count.finish(Map.of(CEMENT, new BigDecimal("450"), STEEL, new BigDecimal("20.5")), ROSA, NOW.plusSeconds(60));

        assertThat(count.status()).isEqualTo(StockCount.Status.DIFFERENCES_FOUND);
        assertThat(count.finishedBy()).isEqualTo(ROSA);
        assertThat(count.finishedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(count.differences()).singleElement().satisfies(line -> {
            assertThat(line.materialId()).isEqualTo(CEMENT);
            assertThat(line.systemQty()).isEqualByComparingTo("450");
            assertThat(line.difference()).isEqualByComparingTo("-5");
        });
    }

    @Test
    void a_count_without_differences_closes_as_matched_and_a_missing_item_counts_as_zero() {
        StockCount count = StockCount.start(WAREHOUSE, List.of(CEMENT, STEEL), null, ROSA, NOW);
        count.registerCounts(Map.of(CEMENT, new BigDecimal("10"), STEEL, BigDecimal.ZERO));

        count.finish(Map.of(CEMENT, new BigDecimal("10")), ROSA, NOW);

        assertThat(count.status()).isEqualTo(StockCount.Status.MATCHED);
        assertThat(count.lines().get(1).systemQty()).isEqualByComparingTo("0");
        assertThat(count.differences()).isEmpty();
        assertThatThrownBy(() -> count.cancel(ROSA, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_NOT_OPEN");
    }

    @Test
    void every_material_must_be_counted_before_finishing() {
        StockCount count = started();
        count.registerCounts(Map.of(CEMENT, BigDecimal.ONE));

        assertThatThrownBy(() -> count.finish(Map.of(), ROSA, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_INCOMPLETE");
        assertThat(count.status()).isEqualTo(StockCount.Status.IN_PROGRESS);
    }

    @Test
    void counted_quantities_are_validated() {
        StockCount count = started();
        assertThatThrownBy(() -> count.registerCounts(Map.of(UUID.randomUUID(), BigDecimal.ONE)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_IN_COUNT");
        assertThatThrownBy(() -> count.registerCounts(Map.of(CEMENT, new BigDecimal("-1"))))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNTED_QUANTITY");
        assertThatThrownBy(() -> count.registerCounts(Map.of(CEMENT, new BigDecimal("1.0001"))))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNTED_QUANTITY");
        java.util.HashMap<UUID, BigDecimal> withNull = new java.util.HashMap<>();
        withNull.put(CEMENT, null);
        assertThatThrownBy(() -> count.registerCounts(withNull))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNTED_QUANTITY");
    }

    @Test
    void only_a_reviewed_difference_is_adjusted_and_the_adjustment_needs_a_reason() {
        StockCount count = withDifferences();

        assertThatThrownBy(() -> count.adjust("Rotura", ROSA, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_NOT_REVIEWED");
        assertThatThrownBy(() -> count.review("  ", ROSA, NOW)).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "REVIEW_NOTE_REQUIRED");
        count.review(" Recontado dos veces ", ROSA, NOW.plusSeconds(10));
        assertThat(count.status()).isEqualTo(StockCount.Status.REVIEWED);
        assertThat(count.reviewNote()).isEqualTo("Recontado dos veces");
        assertThat(count.reviewedBy()).isEqualTo(ROSA);
        assertThat(count.reviewedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> count.adjust(null, ROSA, NOW)).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "ADJUSTMENT_REASON_REQUIRED");

        List<StockCountLine> adjusted = count.adjust("Rotura de bolsas", ROSA, NOW.plusSeconds(20));

        assertThat(adjusted).extracting(StockCountLine::materialId).containsExactly(CEMENT);
        assertThat(count.status()).isEqualTo(StockCount.Status.ADJUSTED);
        assertThat(count.adjustmentReason()).isEqualTo("Rotura de bolsas");
        assertThat(count.adjustedBy()).isEqualTo(ROSA);
        assertThat(count.adjustedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThatThrownBy(() -> count.adjust("otra vez", ROSA, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNT_STATUS");
    }

    @Test
    void transitions_out_of_order_are_rejected() {
        StockCount count = started();
        assertThatThrownBy(() -> count.review("nota", ROSA, NOW)).hasFieldOrPropertyWithValue("code",
                "INVALID_COUNT_STATUS");
        assertThatThrownBy(() -> count.adjust("motivo", ROSA, NOW)).hasFieldOrPropertyWithValue("code",
                "INVALID_COUNT_STATUS");
        StockCount compared = withDifferences();
        assertThatThrownBy(() -> compared.registerCounts(Map.of(CEMENT, BigDecimal.ONE)))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNT_STATUS");
        assertThatThrownBy(() -> compared.finish(Map.of(), ROSA, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNT_STATUS");
    }

    @Test
    void an_open_count_can_be_cancelled_without_adjusting() {
        StockCount count = withDifferences();
        count.cancel(ROSA, NOW.plusSeconds(5));

        assertThat(count.status()).isEqualTo(StockCount.Status.CANCELLED);
        assertThat(count.cancelledBy()).isEqualTo(ROSA);
        assertThat(count.cancelledAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(StockCount.OPEN).doesNotContain(StockCount.Status.CANCELLED);
    }

    @Test
    void a_count_is_rebuilt_from_storage() {
        UUID id = UUID.randomUUID();
        StockCount count = new StockCount(id, WAREHOUSE, StockCount.Status.ADJUSTED, "nota", ROSA, NOW,
                List.of(new StockCountLine(CEMENT, BigDecimal.ONE, BigDecimal.TEN)), 4);
        count.restore(ROSA, NOW, "revisado", ROSA, NOW, "motivo", ROSA, NOW, null, null);

        assertThat(count.id()).isEqualTo(id);
        assertThat(count.version()).isEqualTo(4);
        assertThat(count.reviewNote()).isEqualTo("revisado");
        assertThat(count.adjustmentReason()).isEqualTo("motivo");
        assertThat(count.cancelledBy()).isNull();
        assertThat(count.differences().get(0).difference()).isEqualByComparingTo("-9");
        assertThatThrownBy(() -> new StockCountLine(null, null, null)).isInstanceOf(NullPointerException.class);
    }

    private StockCount withDifferences() {
        StockCount count = started();
        count.registerCounts(Map.of(CEMENT, new BigDecimal("445"), STEEL, new BigDecimal("20")));
        count.finish(Map.of(CEMENT, new BigDecimal("450"), STEEL, new BigDecimal("20")), ROSA, NOW);
        return count;
    }
}
