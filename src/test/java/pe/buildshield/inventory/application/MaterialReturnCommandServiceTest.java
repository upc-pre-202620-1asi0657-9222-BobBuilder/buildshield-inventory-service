package pe.buildshield.inventory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.inventory.application.InventoryEvents.MaterialReturnChanged;
import pe.buildshield.inventory.application.MaterialReturnQueryService.BalanceLineView;
import pe.buildshield.inventory.application.MaterialReturnQueryService.ReturnView;
import pe.buildshield.inventory.application.MaterialReturnQueryService.WorksiteBalanceView;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturn.DeclaredLine;
import pe.buildshield.inventory.domain.model.MaterialReturn.Verification;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ForbiddenException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InMemoryMaterialReturnRepository;
import pe.buildshield.inventory.support.InMemorySiteDirectory;
import pe.buildshield.inventory.support.InMemoryStockRepository;
import pe.buildshield.inventory.support.InMemoryWorksiteSupplyRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Saldo de obra y devolución al almacén de origen (US43, T92) con stock y devoluciones en memoria. */
class MaterialReturnCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T16:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final TenantInfo ADMIN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "ADMINISTRATOR");
    private static final TenantInfo ROSA = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo PEDRO = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo JUAN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "SITE_MANAGER");
    private static final TenantInfo LUIS = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "SITE_MANAGER");
    private static final TenantInfo STRANGER = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "AUDITOR");

    private final InMemoryStockRepository stock = new InMemoryStockRepository();
    private final InMemoryMaterialReturnRepository returns = new InMemoryMaterialReturnRepository();
    private final InMemoryWorksiteSupplyRepository supplies = new InMemoryWorksiteSupplyRepository();
    private final InMemorySiteDirectory directory = new InMemorySiteDirectory();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MaterialReturnCommandService service = new MaterialReturnCommandService(returns, supplies, stock,
            new StockOperations(stock, clock), directory, outbox, clock);
    private final MaterialReturnQueryService queries = new MaterialReturnQueryService(returns, supplies, stock,
            directory);

    private Site central;
    private Site callao;
    private Site torre;
    private Site plaza;
    private MaterialInfo cement;
    private MaterialInfo steel;

    @BeforeEach
    void data() {
        central = directory.warehouse("Almacén Central", true);
        callao = directory.warehouse("Almacén Callao", true);
        torre = directory.worksite("Torre Norte");
        plaza = directory.worksite("Plaza Sur");
        cement = directory.material("CEM-001", true);
        steel = directory.material("ACE-012", true);
        directory.assign(ROSA.userId(), central.id());
        directory.assign(PEDRO.userId(), callao.id());
        directory.assign(JUAN.userId(), torre.id());
        directory.assign(LUIS.userId(), plaza.id());
        stock.put(central.id(), cement.id(), "100", "0");
        stock.put(torre.id(), cement.id(), "30", "0");
        stock.put(torre.id(), steel.id(), "8", "0");
        supplies.recordSupply(torre.id(), cement.id(), central.id(), new BigDecimal("30"));
        supplies.recordSupply(torre.id(), steel.id(), central.id(), new BigDecimal("5"));
        supplies.recordSupply(torre.id(), steel.id(), callao.id(), new BigDecimal("3"));
        TenantContext.set(JUAN);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void the_full_return_reincorporates_only_what_the_warehouse_accepts() {
        MaterialReturn registered = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), new BigDecimal("12"), "2 con humedad")), "Saldo del piso 12");
        assertThat(registered.warehouseId()).isEqualTo(central.id());
        assertThat(registered.status()).isEqualTo(MaterialReturn.Status.REGISTERED);

        service.request(registered.id());
        TenantContext.set(ROSA);
        service.approve(registered.id());
        TenantContext.set(JUAN);
        service.dispatch(registered.id());
        assertThat(item(torre, cement).availableQty()).isEqualByComparingTo("18");

        TenantContext.set(ROSA);
        MaterialReturn received = service.receive(registered.id(), Map.of(cement.id(),
                new Verification(new BigDecimal("10"), "Bolsas húmedas")));

        assertThat(received.status()).isEqualTo(MaterialReturn.Status.RECEIVED);
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("110");
        assertThat(stock.movements).extracting(StockMovement::type)
                .containsExactly(StockMovement.Type.RETURN_OUT, StockMovement.Type.RETURN_IN);
        assertThat(stock.movements.get(1).quantity()).isEqualByComparingTo("10");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(InventoryEvents.MATERIAL_RETURN_RECEIVED), eq(InventoryEvents.MATERIAL_RETURN),
                eq(registered.id()), payload.capture());
        MaterialReturnChanged event = (MaterialReturnChanged) payload.getValue();
        assertThat(event.status()).isEqualTo("RECEIVED");
        assertThat(event.hasRejections()).isTrue();
        assertThat(event.worksiteId()).isEqualTo(torre.id());
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.lines().get(0).acceptedQty()).isEqualByComparingTo("10");
        assertThat(event.lines().get(0).rejectedQty()).isEqualByComparingTo("2");
        assertThat(event.lines().get(0).rejectionReason()).isEqualTo("Bolsas húmedas");
        for (String type : List.of(InventoryEvents.MATERIAL_RETURN_REQUESTED, InventoryEvents.MATERIAL_RETURN_APPROVED,
                InventoryEvents.MATERIAL_RETURN_DISPATCHED)) {
            verify(outbox).append(eq(type), anyString(), eq(registered.id()), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void nothing_accepted_means_nothing_returns_to_the_warehouse() {
        MaterialReturn materialReturn = inTransit(new BigDecimal("5"));
        TenantContext.set(ROSA);
        service.receive(materialReturn.id(), Map.of(cement.id(), new Verification(BigDecimal.ZERO, "Fraguado")));

        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("100");
        assertThat(stock.movements).extracting(StockMovement::type).containsExactly(StockMovement.Type.RETURN_OUT);
    }

    @Test
    void a_rejected_request_leaves_the_balance_at_the_worksite() {
        MaterialReturn materialReturn = service.register(torre.id(), central.id(), List.of(
                new DeclaredLine(cement.id(), new BigDecimal("30"), null)), null);
        service.request(materialReturn.id());
        TenantContext.set(ROSA);
        MaterialReturn rejected = service.reject(materialReturn.id(), "El cemento se usará en la losa");

        assertThat(rejected.status()).isEqualTo(MaterialReturn.Status.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("El cemento se usará en la losa");
        assertThat(item(torre, cement).availableQty()).isEqualByComparingTo("30");
        verify(outbox).append(eq(InventoryEvents.MATERIAL_RETURN_REJECTED), eq(InventoryEvents.MATERIAL_RETURN),
                eq(materialReturn.id()), org.mockito.ArgumentMatchers.any());
        TenantContext.set(JUAN);
        assertThat(service.register(torre.id(), null, List.of(new DeclaredLine(cement.id(), new BigDecimal("30"),
                null)), null).status()).isEqualTo(MaterialReturn.Status.REGISTERED);
    }

    @Test
    void the_declared_balance_cannot_exceed_what_remains_after_pending_returns() {
        service.register(torre.id(), null, List.of(new DeclaredLine(cement.id(), new BigDecimal("20"), null)), null);

        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), new BigDecimal("11"), null)), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "RETURN_EXCEEDS_BALANCE");
        MaterialReturn second = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), new BigDecimal("10"), null)), null);
        stock.put(torre.id(), cement.id(), "25", "0");
        assertThatThrownBy(() -> service.request(second.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_EXCEEDS_BALANCE");
    }

    @Test
    void the_origin_warehouse_is_resolved_from_what_the_worksite_received() {
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(steel.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "ORIGIN_WAREHOUSE_REQUIRED");
        assertThat(service.register(torre.id(), callao.id(), List.of(new DeclaredLine(steel.id(), BigDecimal.ONE,
                null)), null).warehouseId()).isEqualTo(callao.id());
        assertThatThrownBy(() -> service.register(torre.id(), callao.id(), List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "NOT_SUPPLIED_BY_WAREHOUSE");
        MaterialInfo sand = directory.material("ARE-001", true);
        stock.put(torre.id(), sand.id(), "4", "0");
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(sand.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "ORIGIN_WAREHOUSE_UNKNOWN");
        MaterialInfo brick = directory.material("LAD-001", true);
        stock.put(torre.id(), brick.id(), "100", "0");
        supplies.recordSupply(torre.id(), brick.id(), callao.id(), new BigDecimal("100"));
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null), new DeclaredLine(brick.id(), BigDecimal.ONE, null)),
                null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "NO_COMMON_ORIGIN_WAREHOUSE");
    }

    @Test
    void worksites_warehouses_and_materials_are_validated() {
        assertThatThrownBy(() -> service.register(plaza.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WORKSITE_NOT_FOUND");
        assertThatThrownBy(() -> service.register(central.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(), null))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "RETURN_WITHOUT_LINES");
        assertThatThrownBy(() -> service.register(torre.id(), null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(UUID.randomUUID(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_FOUND");
        assertThatThrownBy(() -> service.register(torre.id(), torre.id(), List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "NOT_SUPPLIED_BY_WAREHOUSE");

        Site closed = directory.warehouse("Almacén Cerrado", false);
        MaterialInfo sand = directory.material("ARE-001", true);
        stock.put(torre.id(), sand.id(), "4", "0");
        supplies.recordSupply(torre.id(), sand.id(), closed.id(), new BigDecimal("4"));
        assertThatThrownBy(() -> service.register(torre.id(), null, List.of(
                new DeclaredLine(sand.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SITE_INACTIVE");
        UUID ghost = UUID.randomUUID();
        supplies.recordSupply(torre.id(), sand.id(), ghost, BigDecimal.ONE);
        assertThatThrownBy(() -> service.register(torre.id(), ghost, List.of(
                new DeclaredLine(sand.id(), BigDecimal.ONE, null)), null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WAREHOUSE_NOT_FOUND");
    }

    @Test
    void each_side_does_only_its_own_steps() {
        MaterialReturn materialReturn = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null);
        TenantContext.set(ROSA);
        assertThatThrownBy(() -> service.request(materialReturn.id())).isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_FORBIDDEN");
        assertThatThrownBy(() -> service.cancel(materialReturn.id())).isInstanceOf(ForbiddenException.class);
        TenantContext.set(JUAN);
        service.request(materialReturn.id());
        assertThatThrownBy(() -> service.approve(materialReturn.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.reject(materialReturn.id(), "x")).isInstanceOf(ForbiddenException.class);
        TenantContext.set(PEDRO);
        assertThatThrownBy(() -> service.approve(materialReturn.id())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_NOT_FOUND");
        TenantContext.set(LUIS);
        assertThatThrownBy(() -> service.cancel(materialReturn.id())).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        service.approve(materialReturn.id());
        TenantContext.set(ROSA);
        assertThatThrownBy(() -> service.dispatch(materialReturn.id())).isInstanceOf(ForbiddenException.class);
        TenantContext.set(JUAN);
        service.dispatch(materialReturn.id());
        assertThatThrownBy(() -> service.receive(materialReturn.id(), Map.of())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void the_site_cancels_a_request_and_its_balance_is_free_again() {
        MaterialReturn materialReturn = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), new BigDecimal("30"), null)), null);
        service.request(materialReturn.id());
        MaterialReturn cancelled = service.cancel(materialReturn.id());

        assertThat(cancelled.status()).isEqualTo(MaterialReturn.Status.CANCELLED);
        verify(outbox).append(eq(InventoryEvents.MATERIAL_RETURN_CANCELLED), eq(InventoryEvents.MATERIAL_RETURN),
                eq(materialReturn.id()), org.mockito.ArgumentMatchers.any());
        assertThat(service.register(torre.id(), null, List.of(new DeclaredLine(cement.id(), new BigDecimal("30"),
                null)), null)).isNotNull();
    }

    @Test
    void the_worksite_must_still_have_the_material_when_the_return_leaves() {
        MaterialReturn materialReturn = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), new BigDecimal("30"), null)), null);
        service.request(materialReturn.id());
        TenantContext.set(ROSA);
        service.approve(materialReturn.id());
        TenantContext.set(JUAN);
        stock.put(torre.id(), cement.id(), "10", "0");

        assertThatThrownBy(() -> service.dispatch(materialReturn.id())).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void a_stale_copy_is_rejected() {
        MaterialReturn materialReturn = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, null)), null);
        MaterialReturnCommandService racing = new MaterialReturnCommandService(new InMemoryMaterialReturnRepository() {
            @Override
            public java.util.Optional<MaterialReturn> find(UUID returnId) {
                return returns.find(returnId);
            }

            @Override
            public List<MaterialReturn> findByLocations(java.util.Collection<UUID> worksiteIds,
                    java.util.Collection<UUID> warehouseIds, MaterialReturn.Status status) {
                return returns.findByLocations(worksiteIds, warehouseIds, status);
            }

            @Override
            public boolean update(MaterialReturn changed) {
                return false;
            }
        }, supplies, stock, new StockOperations(stock, clock), directory, outbox, clock);

        assertThatThrownBy(() -> racing.request(materialReturn.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_MODIFIED");
    }

    @Test
    void the_balance_shows_what_can_still_be_returned_and_where_it_came_from() {
        service.register(torre.id(), null, List.of(new DeclaredLine(cement.id(), new BigDecimal("12"), null)), null);
        stock.put(torre.id(), UUID.randomUUID(), "1", "0");
        stock.put(torre.id(), directory.material("ZZZ-000", true).id(), "0", "0");

        WorksiteBalanceView balance = queries.balance(torre.id());

        assertThat(balance.worksiteName()).isEqualTo("Torre Norte");
        assertThat(balance.lines()).extracting(BalanceLineView::sku).containsExactly("ACE-012", "CEM-001");
        BalanceLineView cementLine = balance.lines().get(1);
        assertThat(cementLine.availableQty()).isEqualByComparingTo("30");
        assertThat(cementLine.pendingReturnQty()).isEqualByComparingTo("12");
        assertThat(cementLine.returnableQty()).isEqualByComparingTo("18");
        assertThat(cementLine.originWarehouses()).extracting(MaterialReturnQueryService.OriginView::warehouseName)
                .containsExactly("Almacén Central");
        assertThat(balance.lines().get(0).originWarehouses()).extracting(MaterialReturnQueryService.OriginView::warehouseName)
                .containsExactly("Almacén Callao", "Almacén Central");

        TenantContext.set(ROSA);
        assertThat(queries.balance(torre.id()).lines()).hasSize(2);
        TenantContext.set(LUIS);
        assertThatThrownBy(() -> queries.balance(torre.id())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "WORKSITE_NOT_FOUND");
        TenantContext.set(STRANGER);
        assertThatThrownBy(() -> queries.balance(torre.id())).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        assertThatThrownBy(() -> queries.balance(central.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void each_role_lists_the_returns_of_its_places() {
        MaterialReturn toCentral = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), BigDecimal.ONE, "detalle")), null);
        MaterialReturn toCallao = service.register(torre.id(), callao.id(), List.of(
                new DeclaredLine(steel.id(), BigDecimal.ONE, null)), null);
        service.request(toCallao.id());

        assertThat(queries.list(null, null, null)).extracting(ReturnView::id)
                .containsExactlyInAnyOrder(toCentral.id(), toCallao.id());
        assertThat(queries.list(MaterialReturn.Status.REQUESTED, null, null)).extracting(ReturnView::id)
                .containsExactly(toCallao.id());
        TenantContext.set(ROSA);
        assertThat(queries.list(null, null, null)).extracting(ReturnView::id).containsExactly(toCentral.id());
        assertThat(queries.list(null, null, callao.id())).isEmpty();
        TenantContext.set(PEDRO);
        assertThat(queries.list(null, torre.id(), null)).extracting(ReturnView::id).containsExactly(toCallao.id());
        TenantContext.set(LUIS);
        assertThat(queries.list(null, null, null)).isEmpty();
        assertThatThrownBy(() -> queries.get(toCentral.id())).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(STRANGER);
        assertThat(queries.list(null, null, null)).isEmpty();
        assertThatThrownBy(() -> queries.view(toCentral)).isInstanceOf(ResourceNotFoundException.class);
        TenantContext.set(ADMIN);
        assertThat(queries.list(null, null, null)).hasSize(2);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_NOT_FOUND");

        ReturnView view = queries.get(toCentral.id());
        assertThat(view.worksiteName()).isEqualTo("Torre Norte");
        assertThat(view.warehouseName()).isEqualTo("Almacén Central");
        assertThat(view.status()).isEqualTo("REGISTERED");
        assertThat(view.lines().get(0).sku()).isEqualTo("CEM-001");
        assertThat(view.lines().get(0).detail()).isEqualTo("detalle");
        assertThat(view.hasRejections()).isFalse();
    }

    private MaterialReturn inTransit(BigDecimal quantity) {
        MaterialReturn materialReturn = service.register(torre.id(), null, List.of(
                new DeclaredLine(cement.id(), quantity, null)), null);
        service.request(materialReturn.id());
        TenantContext.set(ROSA);
        service.approve(materialReturn.id());
        TenantContext.set(JUAN);
        return service.dispatch(materialReturn.id());
    }

    private StockItem item(Site site, MaterialInfo material) {
        return stock.find(site.id(), material.id()).orElseThrow();
    }
}
