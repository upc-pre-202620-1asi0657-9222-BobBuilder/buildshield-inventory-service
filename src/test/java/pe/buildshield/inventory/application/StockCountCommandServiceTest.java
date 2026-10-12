package pe.buildshield.inventory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.inventory.application.InventoryEvents.StockAdjusted;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.application.StockCountQueryService.CountView;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InMemorySiteDirectory;
import pe.buildshield.inventory.support.InMemoryStockCountRepository;
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
import static org.mockito.Mockito.verify;

/** Conteo de validación y ajuste (US41, T91) con stock y conteos en memoria. */
class StockCountCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final TenantInfo ADMIN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "ADMINISTRATOR");
    private static final TenantInfo ROSA = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo PEDRO = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo JUAN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "SITE_MANAGER");

    private final InMemoryStockRepository stock = new InMemoryStockRepository();
    private final InMemoryStockCountRepository counts = new InMemoryStockCountRepository();
    private final InMemorySiteDirectory directory = new InMemorySiteDirectory();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StockCountCommandService service = new StockCountCommandService(counts, stock,
            new StockOperations(stock, clock), directory, outbox, clock);
    private final StockCountQueryService queries = new StockCountQueryService(counts, directory);

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

    @Test
    void the_manager_counts_finds_a_difference_reviews_it_and_adjusts_the_inventory() {
        StockCount count = service.start(central.id(), List.of(), "Conteo mensual");
        assertThat(count.lines()).extracting(line -> line.materialId()).containsExactlyInAnyOrder(cement.id(), steel.id());

        service.registerCounts(count.id(), Map.of(cement.id(), new BigDecimal("445"), steel.id(), new BigDecimal("22")));
        StockCount compared = service.finish(count.id());
        assertThat(compared.status()).isEqualTo(StockCount.Status.DIFFERENCES_FOUND);
        assertThat(compared.differences()).hasSize(2);

        service.review(count.id(), "Recontado; 5 bolsas rotas y 2 varillas sin guía");
        StockCount adjusted = service.adjust(count.id(), "Rotura y sobrante sin guía");

        assertThat(adjusted.status()).isEqualTo(StockCount.Status.ADJUSTED);
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("395");
        assertThat(item(central, cement).reservedQty()).isEqualByComparingTo("50");
        assertThat(item(central, steel).availableQty()).isEqualByComparingTo("22");
        assertThat(stock.movements).extracting(StockMovement::type)
                .containsExactlyInAnyOrder(StockMovement.Type.ADJUSTMENT_OUT, StockMovement.Type.ADJUSTMENT_IN);
        assertThat(stock.movements.get(0).reference()).startsWith("Ajuste por conteo ")
                .endsWith(": Rotura y sobrante sin guía");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(InventoryEvents.STOCK_ADJUSTED), eq(InventoryEvents.STOCK_COUNT), eq(count.id()),
                payload.capture());
        StockAdjusted event = (StockAdjusted) payload.getValue();
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.reason()).isEqualTo("Rotura y sobrante sin guía");
        assertThat(event.reviewNote()).startsWith("Recontado");
        InventoryEvents.AdjustedLine cementLine = event.lines().stream()
                .filter(line -> line.materialId().equals(cement.id())).findFirst().orElseThrow();
        assertThat(cementLine.systemQty()).isEqualByComparingTo("450");
        assertThat(cementLine.countedQty()).isEqualByComparingTo("445");
        assertThat(cementLine.difference()).isEqualByComparingTo("-5");
        assertThat(cementLine.availableAfter()).isEqualByComparingTo("395");
    }

    @Test
    void a_count_without_differences_closes_and_nothing_is_adjusted() {
        StockCount count = service.start(central.id(), List.of(steel.id()), null);
        service.registerCounts(count.id(), Map.of(steel.id(), new BigDecimal("20")));

        assertThat(service.finish(count.id()).status()).isEqualTo(StockCount.Status.MATCHED);
        assertThatThrownBy(() -> service.adjust(count.id(), "nada")).isInstanceOf(ConflictException.class);
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void a_difference_not_reviewed_is_not_adjusted() {
        StockCount count = counted(central, cement, "440");

        assertThatThrownBy(() -> service.adjust(count.id(), "Rotura")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_NOT_REVIEWED");
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("400");
    }

    @Test
    void a_shortage_larger_than_what_is_available_is_not_adjusted() {
        StockCount count = counted(central, cement, "10");
        service.review(count.id(), "Revisado");

        assertThatThrownBy(() -> service.adjust(count.id(), "Pérdida")).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void a_material_without_stock_counts_against_zero() {
        MaterialInfo sand = directory.material("ARE-001", true);
        StockCount count = counted(central, sand, "3");
        service.review(count.id(), "Arena sin registrar");
        service.adjust(count.id(), "Alta por conteo");

        assertThat(item(central, sand).availableQty()).isEqualByComparingTo("3");
    }

    @Test
    void only_one_open_count_per_warehouse() {
        service.start(central.id(), List.of(cement.id()), null);

        assertThatThrownBy(() -> service.start(central.id(), List.of(steel.id()), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "COUNT_ALREADY_OPEN");
    }

    @Test
    void a_count_is_cancelled_without_touching_the_stock() {
        StockCount count = counted(central, cement, "1");
        StockCount cancelled = service.cancel(count.id());

        assertThat(cancelled.status()).isEqualTo(StockCount.Status.CANCELLED);
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("400");
        assertThat(service.start(central.id(), List.of(cement.id()), null).status())
                .isEqualTo(StockCount.Status.IN_PROGRESS);
    }

    @Test
    void warehouses_and_materials_must_exist_be_visible_and_active() {
        assertThatThrownBy(() -> service.start(callao.id(), List.of(cement.id()), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WAREHOUSE_NOT_FOUND");
        TenantContext.set(JUAN);
        assertThatThrownBy(() -> service.start(central.id(), List.of(cement.id()), null))
                .isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        assertThatThrownBy(() -> service.start(central.id(), List.of(UUID.randomUUID()), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_FOUND");
        Site closed = directory.warehouse("Almacén Cerrado", false);
        assertThatThrownBy(() -> service.start(closed.id(), List.of(cement.id()), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SITE_INACTIVE");
        assertThatThrownBy(() -> service.start(callao.id(), null, null))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "COUNT_WITHOUT_MATERIALS");
    }

    @Test
    void another_manager_does_not_see_the_count_and_a_stale_copy_is_rejected() {
        StockCount count = service.start(central.id(), List.of(cement.id()), null);
        TenantContext.set(PEDRO);
        assertThatThrownBy(() -> service.finish(count.id())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_NOT_FOUND");
        assertThatThrownBy(() -> queries.get(count.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);

        TenantContext.set(ROSA);
        StockCount stale = counts.find(count.id()).orElseThrow();
        service.registerCounts(count.id(), Map.of(cement.id(), BigDecimal.ONE));
        stale.registerCounts(Map.of(cement.id(), BigDecimal.TEN));
        assertThat(counts.update(stale)).isFalse();
        StockCountCommandService racing = new StockCountCommandService(new InMemoryStockCountRepository() {
            @Override
            public java.util.Optional<StockCount> find(UUID countId) {
                return counts.find(countId);
            }

            @Override
            public boolean update(StockCount changed) {
                return false;
            }
        }, stock, new StockOperations(stock, clock), directory, outbox, clock);
        assertThatThrownBy(() -> racing.cancel(count.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "COUNT_MODIFIED");
    }

    @Test
    void queries_show_each_manager_only_the_counts_of_their_warehouses() {
        StockCount count = counted(central, cement, "445");
        TenantContext.set(PEDRO);
        StockCount other = service.start(callao.id(), List.of(steel.id()), "Callao");

        assertThat(queries.list(null, null)).extracting(CountView::id).containsExactly(other.id());
        TenantContext.set(ADMIN);
        assertThat(queries.list(null, null)).extracting(CountView::id).containsExactlyInAnyOrder(count.id(), other.id());
        assertThat(queries.list(central.id(), StockCount.Status.DIFFERENCES_FOUND)).extracting(CountView::id)
                .containsExactly(count.id());
        assertThat(queries.list(central.id(), StockCount.Status.ADJUSTED)).isEmpty();
        assertThat(queries.list(UUID.randomUUID(), null)).isEmpty();

        CountView view = queries.get(count.id());
        assertThat(view.warehouseName()).isEqualTo("Almacén Central");
        assertThat(view.status()).isEqualTo("DIFFERENCES_FOUND");
        assertThat(view.differenceCount()).isEqualTo(1);
        assertThat(view.lines().get(0).sku()).isEqualTo("CEM-001");
        assertThat(view.lines().get(0).difference()).isEqualByComparingTo("-5");

        TenantContext.set(JUAN);
        assertThat(queries.list(null, null)).isEmpty();
    }

    @Test
    void the_view_shows_lines_of_materials_no_longer_in_the_catalog_without_names() {
        StockCount count = service.start(central.id(), List.of(cement.id()), null);
        InMemorySiteDirectory empty = new InMemorySiteDirectory() {
            @Override
            public Map<UUID, MaterialInfo> findMaterials(java.util.Collection<UUID> materialIds) {
                return Map.of();
            }

            @Override
            public List<Site> locations(String type) {
                return directory.locations(type);
            }

            @Override
            public java.util.Set<UUID> assignedLocations(UUID userId) {
                return directory.assignedLocations(userId);
            }
        };
        CountView view = new StockCountQueryService(counts, empty).view(count);
        assertThat(view.lines().get(0).sku()).isNull();
        assertThat(view.lines().get(0).materialName()).isNull();
    }

    private StockCount counted(Site warehouse, MaterialInfo material, String counted) {
        StockCount count = service.start(warehouse.id(), List.of(material.id()), null);
        service.registerCounts(count.id(), Map.of(material.id(), new BigDecimal(counted)));
        return service.finish(count.id());
    }

    private StockItem item(Site site, MaterialInfo material) {
        return stock.find(site.id(), material.id()).orElseThrow();
    }
}
