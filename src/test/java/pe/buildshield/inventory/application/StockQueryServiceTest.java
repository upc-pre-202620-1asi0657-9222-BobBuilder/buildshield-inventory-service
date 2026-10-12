package pe.buildshield.inventory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.inventory.application.OrderingSignals.DispatchDeparted;
import pe.buildshield.inventory.application.OrderingSignals.OrderApproved;
import pe.buildshield.inventory.application.OrderingSignals.OrderLine;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.application.StockQueryService.MovementHistoryView;
import pe.buildshield.inventory.application.StockQueryService.MovementView;
import pe.buildshield.inventory.application.StockQueryService.ReservationView;
import pe.buildshield.inventory.application.StockQueryService.StockView;
import pe.buildshield.inventory.application.StockQueryService.WorksiteStockView;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InMemorySiteDirectory;
import pe.buildshield.inventory.support.InMemoryStockRepository;
import pe.buildshield.inventory.support.InMemoryWorksiteSupplyRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Visibilidad de cada rol en las consultas de stock, reservas y movimientos. */
class StockQueryServiceTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final TenantInfo ADMIN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "ADMINISTRATOR");
    private static final TenantInfo ROSA = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");
    private static final TenantInfo JUAN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "SITE_MANAGER");
    private static final TenantInfo STRANGER = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "AUDITOR");

    private final InMemoryStockRepository stock = new InMemoryStockRepository();
    private final InMemorySiteDirectory directory = new InMemorySiteDirectory();
    private final StockQueryService queries = new StockQueryService(stock, directory);

    private Site central;
    private Site callao;
    private Site closed;
    private Site torre;
    private Site plaza;
    private MaterialInfo cement;
    private UUID order;

    @BeforeEach
    void data() {
        central = directory.warehouse("Almacén Central", true);
        callao = directory.warehouse("Almacén Callao", true);
        closed = directory.warehouse("Almacén Cerrado", false);
        torre = directory.worksite("Torre Norte");
        plaza = directory.worksite("Plaza Sur");
        cement = directory.material("CEM-001", true);
        directory.assign(ROSA.userId(), central.id());
        directory.assign(JUAN.userId(), torre.id());
        stock.put(central.id(), cement.id(), "100", "0");
        stock.put(callao.id(), cement.id(), "50", "0");
        stock.put(closed.id(), cement.id(), "5", "0");
        stock.put(torre.id(), cement.id(), "30", "0");
        stock.put(plaza.id(), cement.id(), "8", "0");
        stock.put(central.id(), UUID.randomUUID(), "1", "0");
        TenantContext.set(ADMIN);
        StockCommandService commands = new StockCommandService(new StockOperations(stock, Clock.systemUTC()), stock,
                directory, mock(OutboxWriter.class), new InMemoryWorksiteSupplyRepository());
        order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        commands.reserveForOrder(new OrderApproved(order, central.id(), List.of(
                new OrderLine(line, cement.id(), new BigDecimal("40")))));
        commands.reserveForOrder(new OrderApproved(UUID.randomUUID(), callao.id(), List.of(
                new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("10")))));
        commands.deductForDispatch(new DispatchDeparted(UUID.randomUUID(), order, central.id(), "MAN-1",
                List.of(new OrderLine(line, cement.id(), new BigDecimal("15")))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void the_administrator_sees_every_warehouse_and_can_filter_one() {
        assertThat(queries.list(null)).extracting(StockView::warehouseName)
                .containsExactlyInAnyOrder("Almacén Central", "Almacén Callao", "Almacén Cerrado");
        StockView only = queries.list(central.id()).get(0);
        assertThat(queries.list(central.id())).hasSize(1);
        assertThat(only.sku()).isEqualTo("CEM-001");
        assertThat(only.materialName()).isEqualTo("Material CEM-001");
        assertThat(only.unit()).isEqualTo("BAG");
        assertThat(only.availableQty()).isEqualByComparingTo("60");
        assertThat(only.reservedQty()).isEqualByComparingTo("25");
    }

    @Test
    void each_manager_sees_the_warehouses_of_the_core_rules() {
        TenantContext.set(ROSA);
        assertThat(queries.list(null)).extracting(StockView::warehouseId).containsExactly(central.id());
        assertThat(queries.list(callao.id())).isEmpty();
        TenantContext.set(JUAN);
        assertThat(queries.list(null)).extracting(StockView::warehouseName)
                .containsExactlyInAnyOrder("Almacén Central", "Almacén Callao");
        TenantContext.set(STRANGER);
        assertThat(queries.list(null)).isEmpty();
    }

    @Test
    void worksite_stock_is_visible_to_its_site_manager_and_to_warehouse_staff() {
        TenantContext.set(JUAN);
        List<WorksiteStockView> mine = queries.listWorksites(null);
        assertThat(mine).extracting(WorksiteStockView::worksiteName).containsExactly("Torre Norte");
        assertThat(mine.get(0).availableQty()).isEqualByComparingTo("30");
        assertThat(queries.listWorksites(plaza.id())).isEmpty();
        TenantContext.set(ROSA);
        assertThat(queries.listWorksites(null)).hasSize(2);
        TenantContext.set(ADMIN);
        assertThat(queries.listWorksites(plaza.id())).extracting(WorksiteStockView::sku).containsExactly("CEM-001");
        TenantContext.set(STRANGER);
        assertThat(queries.listWorksites(null)).isEmpty();
    }

    @Test
    void reservations_show_what_remains_and_follow_warehouse_visibility() {
        List<ReservationView> all = queries.reservations(null, null);
        assertThat(all).hasSize(2);
        ReservationView ofOrder = queries.reservations(order, null).get(0);
        assertThat(ofOrder.quantity()).isEqualByComparingTo("40");
        assertThat(ofOrder.consumedQty()).isEqualByComparingTo("15");
        assertThat(ofOrder.remainingQty()).isEqualByComparingTo("25");
        assertThat(ofOrder.status()).isEqualTo("RESERVED");
        assertThat(ofOrder.warehouseName()).isEqualTo("Almacén Central");
        assertThat(ofOrder.sku()).isEqualTo("CEM-001");

        TenantContext.set(ROSA);
        assertThat(queries.reservations(null, null)).extracting(ReservationView::warehouseId).containsExactly(central.id());
        assertThat(queries.reservations(null, callao.id())).isEmpty();
    }

    @Test
    void movements_list_the_newest_first_within_visible_places() {
        List<MovementView> all = queries.movements(central.id(), cement.id());
        assertThat(all).extracting(MovementView::type).containsExactly("DISPATCH", "RESERVE");
        assertThat(all.get(0).reference()).isEqualTo("Despacho MAN-1");
        assertThat(all.get(0).locationType()).isEqualTo("WAREHOUSE");
        assertThat(all.get(0).locationName()).isEqualTo("Almacén Central");
        assertThat(all.get(0).sku()).isEqualTo("CEM-001");

        TenantContext.set(ROSA);
        assertThat(queries.movements(callao.id(), null)).isEmpty();
        TenantContext.set(JUAN);
        assertThat(queries.movements(callao.id(), null)).extracting(MovementView::type).containsExactly("RESERVE");
        assertThat(queries.movements(closed.id(), null)).isEmpty();
        TenantContext.set(STRANGER);
        assertThat(queries.movements(null, null)).isEmpty();
        assertThat(queries.reservations(null, null)).isEmpty();
    }

    @Test
    void the_movement_history_is_paged_newest_first_and_filtered_by_type_and_dates() {
        MovementHistoryView first = queries.movementHistory(null, null, null, null, null, 0, 2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).hasSize(2);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.items().get(0).type()).isEqualTo("DISPATCH");
        MovementHistoryView second = queries.movementHistory(null, null, null, null, null, 1, 2);
        assertThat(second.items()).hasSize(1);

        MovementHistoryView reserves = queries.movementHistory(central.id(), cement.id(), " reserve ",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), 0, 20);
        assertThat(reserves.items()).extracting(MovementView::type).containsExactly("RESERVE");
        assertThat(queries.movementHistory(null, null, "", Instant.now().plusSeconds(3600), null, 0, 20).total())
                .isZero();

        TenantContext.set(STRANGER);
        MovementHistoryView none = queries.movementHistory(null, null, null, null, null, 0, 20);
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    void the_history_rejects_invalid_pages_types_and_ranges() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> queries.movementHistory(null, null, null, null, null, -1, 20))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "INVALID_PAGE");
        assertThatThrownBy(() -> queries.movementHistory(null, null, null, null, null, 0, 0))
                .hasFieldOrPropertyWithValue("code", "INVALID_PAGE");
        assertThatThrownBy(() -> queries.movementHistory(null, null, null, null, null, 0, 201))
                .hasFieldOrPropertyWithValue("code", "INVALID_PAGE");
        assertThatThrownBy(() -> queries.movementHistory(null, null, "TELEPORT", null, null, 0, 20))
                .hasFieldOrPropertyWithValue("code", "INVALID_MOVEMENT_TYPE");
        assertThatThrownBy(() -> queries.movementHistory(null, null, null, now, now, 0, 20))
                .hasFieldOrPropertyWithValue("code", "INVALID_RANGE");
    }
}
