package pe.buildshield.inventory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.inventory.application.InventoryEvents.StockAdjusted;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.application.StockAdjustmentCommandService.CountedLine;
import pe.buildshield.inventory.application.StockAdjustmentCommandService.CountedResult;
import pe.buildshield.inventory.application.StockAdjustmentQueryService.AdjustmentView;
import pe.buildshield.inventory.application.StockAdjustmentQueryService.CountResultView;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.domain.model.StockAdjustment;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InMemorySiteDirectory;
import pe.buildshield.inventory.support.InMemoryStockAdjustmentRepository;
import pe.buildshield.inventory.support.InMemoryStockRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Conteo físico y ajuste (US41, T91) con stock y ajustes en memoria. */
class StockAdjustmentCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final TenantInfo ADMIN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "ADMINISTRATOR");
    private static final TenantInfo ROSA = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo PEDRO = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo JUAN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "SITE_MANAGER");

    private final InMemoryStockRepository stock = new InMemoryStockRepository();
    private final InMemoryStockAdjustmentRepository adjustments = new InMemoryStockAdjustmentRepository();
    private final InMemorySiteDirectory directory = new InMemorySiteDirectory();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StockAdjustmentCommandService service = new StockAdjustmentCommandService(adjustments, stock,
            new StockOperations(stock, clock), directory, outbox, clock);
    private final StockAdjustmentQueryService queries = new StockAdjustmentQueryService(adjustments, directory);

    private Site central;
    private Site callao;
    private MaterialInfo cement;
    private MaterialInfo steel;

    @BeforeEach
    void data() {
        central = directory.warehouse("Almacén Central", true);
        callao = directory.warehouse("Almacén Callao", true);
        cement = directory.material("CEM-001", true);
        steel = directory.material("ACE-012", true);
        directory.assign(ROSA.userId(), central.id());
        directory.assign(PEDRO.userId(), callao.id());
        stock.put(central.id(), cement.id(), "400", "50");
        stock.put(central.id(), steel.id(), "20", "0");
        TenantContext.set(ROSA);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static List<CountedLine> counted(MaterialInfo material, String quantity) {
        return List.of(new CountedLine(material.id(), new BigDecimal(quantity)));
    }

    @Test
    void comparing_counts_against_available_plus_reserved_and_saves_nothing() {
        CountedResult compared = service.compare(central.id(), List.of(
                new CountedLine(cement.id(), new BigDecimal("445")), new CountedLine(steel.id(), new BigDecimal("20"))));

        assertThat(compared.status()).isEqualTo("DIFFERENCES_FOUND");
        assertThat(compared.lines().get(0).systemQty()).isEqualByComparingTo("450");
        assertThat(compared.lines().get(0).difference()).isEqualByComparingTo("-5");
        assertThat(compared.lines().get(0).adjustmentId()).isNull();
        assertThat(service.compare(central.id(), counted(steel, "20")).status()).isEqualTo("MATCHED");
        assertThat(adjustments.stored).isEmpty();
        assertThat(stock.movements).isEmpty();
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("400");
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void applying_saves_one_adjustment_per_material_and_moves_only_the_differences() {
        CountedResult applied = service.apply(central.id(), List.of(new CountedLine(cement.id(), new BigDecimal("445")),
                new CountedLine(steel.id(), new BigDecimal("22"))), "  Rotura y sobrante sin guía ");

        assertThat(applied.status()).isEqualTo("ADJUSTED");
        assertThat(applied.reason()).isEqualTo("Rotura y sobrante sin guía");
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("395");
        assertThat(item(central, cement).reservedQty()).isEqualByComparingTo("50");
        assertThat(item(central, steel).availableQty()).isEqualByComparingTo("22");
        assertThat(stock.movements).extracting(StockMovement::type)
                .containsExactly(StockMovement.Type.ADJUSTMENT_OUT, StockMovement.Type.ADJUSTMENT_IN);

        StockAdjustment first = adjustments.stored.get(0);
        assertThat(stock.movements.get(0).sourceRef()).isEqualTo(first.id());
        assertThat(first.stockItemId()).isEqualTo(item(central, cement).id());
        assertThat(first.countedQty()).isEqualByComparingTo("445");
        assertThat(first.systemQty()).isEqualByComparingTo("450");
        assertThat(first.reason()).isEqualTo("Rotura y sobrante sin guía");
        assertThat(first.createdBy()).isEqualTo(ROSA.userId());
        assertThat(first.createdAt()).isEqualTo(NOW);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).append(eq(InventoryEvents.STOCK_ADJUSTED), eq(InventoryEvents.STOCK_ITEM), any(),
                payload.capture());
        StockAdjusted event = (StockAdjusted) payload.getAllValues().get(0);
        assertThat(event.countId()).isEqualTo(first.id());
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.reason()).isEqualTo("Rotura y sobrante sin guía");
        assertThat(event.reviewNote()).isNull();
        assertThat(event.lines()).singleElement().satisfies(line -> {
            assertThat(line.systemQty()).isEqualByComparingTo("450");
            assertThat(line.countedQty()).isEqualByComparingTo("445");
            assertThat(line.difference()).isEqualByComparingTo("-5");
            assertThat(line.availableAfter()).isEqualByComparingTo("395");
        });
    }

    @Test
    void a_count_without_differences_is_kept_as_evidence_and_changes_nothing() {
        CountedResult applied = service.apply(central.id(), counted(steel, "20"), null);

        assertThat(applied.status()).isEqualTo("MATCHED");
        assertThat(adjustments.stored).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.reason()).isEqualTo("Conteo sin diferencias");
            assertThat(adjustment.difference()).isZero();
        });
        assertThat(stock.movements).isEmpty();
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void a_difference_needs_a_reason_and_nothing_is_adjusted_without_it() {
        for (String reason : new String[]{null, "   "}) {
            assertThatThrownBy(() -> service.apply(central.id(), counted(cement, "440"), reason))
                    .isInstanceOf(ValidationException.class)
                    .hasFieldOrPropertyWithValue("code", "ADJUSTMENT_REASON_REQUIRED");
        }
        assertThatThrownBy(() -> service.apply(central.id(), counted(cement, "440"), "x".repeat(201)))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "TEXT_TOO_LONG");
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("400");
        assertThat(adjustments.stored).isEmpty();
    }

    @Test
    void a_shortage_larger_than_what_is_available_is_not_adjusted() {
        assertThatThrownBy(() -> service.apply(central.id(), counted(cement, "10"), "Pérdida"))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void a_material_without_stock_counts_against_zero() {
        MaterialInfo sand = directory.material("ARE-001", true);
        service.apply(central.id(), counted(sand, "3"), "Alta por conteo");
        assertThat(item(central, sand).availableQty()).isEqualByComparingTo("3");
        assertThat(adjustments.stored).hasSize(1);

        MaterialInfo gravel = directory.material("GRA-001", true);
        CountedResult zero = service.apply(central.id(), counted(gravel, "0"), null);
        assertThat(zero.status()).isEqualTo("MATCHED");
        assertThat(zero.lines().get(0).adjustmentId()).isNull();
        assertThat(adjustments.stored).hasSize(1);
        assertThat(stock.find(central.id(), gravel.id())).isEmpty();
    }

    @Test
    void warehouses_lines_and_materials_are_validated() {
        assertThatThrownBy(() -> service.compare(callao.id(), counted(cement, "1")))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WAREHOUSE_NOT_FOUND");
        TenantContext.set(JUAN);
        assertThatThrownBy(() -> service.apply(central.id(), counted(cement, "1"), "x"))
                .isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        assertThatThrownBy(() -> service.compare(central.id(), List.of(new CountedLine(UUID.randomUUID(), BigDecimal.ONE))))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_FOUND");
        Site closed = directory.warehouse("Almacén Cerrado", false);
        assertThatThrownBy(() -> service.compare(closed.id(), counted(cement, "1")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SITE_INACTIVE");
        assertThatThrownBy(() -> service.compare(central.id(), List.of()))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "COUNT_WITHOUT_MATERIALS");
        assertThatThrownBy(() -> service.compare(central.id(), null))
                .hasFieldOrPropertyWithValue("code", "COUNT_WITHOUT_MATERIALS");
        assertThatThrownBy(() -> service.compare(central.id(), List.of(new CountedLine(cement.id(), BigDecimal.ONE),
                new CountedLine(cement.id(), BigDecimal.TEN))))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "DUPLICATED_MATERIAL");
        assertThatThrownBy(() -> service.compare(central.id(), counted(cement, "-1")))
                .hasFieldOrPropertyWithValue("code", "INVALID_COUNTED_QUANTITY");
    }

    @Test
    void queries_show_each_manager_only_the_adjustments_of_their_warehouses() {
        service.apply(central.id(), counted(cement, "445"), "Rotura");
        UUID ofCentral = adjustments.stored.get(0).id();
        TenantContext.set(PEDRO);
        stock.put(callao.id(), steel.id(), "5", "0");
        service.apply(callao.id(), counted(steel, "5"), null);
        UUID ofCallao = adjustments.stored.get(1).id();

        assertThat(queries.list(null, null)).extracting(AdjustmentView::id).containsExactly(ofCallao);
        assertThatThrownBy(() -> queries.get(ofCentral)).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_NOT_FOUND");
        assertThatThrownBy(() -> queries.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        assertThat(queries.list(null, null)).extracting(AdjustmentView::id).containsExactly(ofCallao, ofCentral);
        assertThat(queries.list(central.id(), null)).extracting(AdjustmentView::id).containsExactly(ofCentral);
        assertThat(queries.list(null, steel.id())).extracting(AdjustmentView::id).containsExactly(ofCallao);
        assertThat(queries.list(UUID.randomUUID(), null)).isEmpty();

        AdjustmentView view = queries.get(ofCentral);
        assertThat(view.warehouseName()).isEqualTo("Almacén Central");
        assertThat(view.sku()).isEqualTo("CEM-001");
        assertThat(view.difference()).isEqualByComparingTo("-5");
        assertThat(view.reason()).isEqualTo("Rotura");

        TenantContext.set(JUAN);
        assertThat(queries.list(null, null)).isEmpty();
    }

    @Test
    void the_result_view_counts_differences_and_tolerates_materials_no_longer_in_the_catalog() {
        CountResultView view = queries.view(service.compare(central.id(), List.of(
                new CountedLine(cement.id(), new BigDecimal("445")), new CountedLine(steel.id(), new BigDecimal("20")))));
        assertThat(view.warehouseName()).isEqualTo("Almacén Central");
        assertThat(view.status()).isEqualTo("DIFFERENCES_FOUND");
        assertThat(view.differenceCount()).isEqualTo(1);
        assertThat(view.lines().get(0).sku()).isEqualTo("CEM-001");

        StockAdjustmentQueryService empty = new StockAdjustmentQueryService(adjustments, new InMemorySiteDirectory() {
            @Override
            public Map<UUID, MaterialInfo> findMaterials(java.util.Collection<UUID> materialIds) {
                return Map.of();
            }
        });
        CountResultView withoutCatalog = empty.view(service.compare(central.id(), counted(cement, "450")));
        assertThat(withoutCatalog.warehouseName()).isNull();
        assertThat(withoutCatalog.lines().get(0).sku()).isNull();
    }

    private StockItem item(Site site, MaterialInfo material) {
        return stock.find(site.id(), material.id()).orElseThrow();
    }
}
