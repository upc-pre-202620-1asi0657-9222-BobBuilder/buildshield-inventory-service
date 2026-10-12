package pe.buildshield.inventory.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.inventory.application.InventoryEvents.StockDeducted;
import pe.buildshield.inventory.application.InventoryEvents.StockEntryRegistered;
import pe.buildshield.inventory.application.InventoryEvents.StockReceived;
import pe.buildshield.inventory.application.InventoryEvents.StockReleased;
import pe.buildshield.inventory.application.InventoryEvents.StockReservationRejected;
import pe.buildshield.inventory.application.InventoryEvents.StockReserved;
import pe.buildshield.inventory.application.OrderingSignals.DispatchDeparted;
import pe.buildshield.inventory.application.OrderingSignals.OrderApproved;
import pe.buildshield.inventory.application.OrderingSignals.OrderEnded;
import pe.buildshield.inventory.application.OrderingSignals.OrderLine;
import pe.buildshield.inventory.application.OrderingSignals.ReceivedLine;
import pe.buildshield.inventory.application.OrderingSignals.ReceptionReceived;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.EntryType;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InMemorySiteDirectory;
import pe.buildshield.inventory.support.InMemoryStockRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Carga de existencias y pasos de inventory en la saga de reserva, con stock en memoria. */
class StockCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final TenantInfo ADMIN = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "ADMINISTRATOR");
    private static final TenantInfo ROSA = new TenantInfo(ORGANIZATION, UUID.randomUUID(), "WAREHOUSE_MANAGER");

    private final InMemoryStockRepository stock = new InMemoryStockRepository();
    private final InMemorySiteDirectory directory = new InMemorySiteDirectory();
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final StockCommandService service = new StockCommandService(
            new StockOperations(stock, Clock.fixed(NOW, ZoneOffset.UTC)), stock, directory, outbox);

    private Site central;
    private Site torre;
    private MaterialInfo cement;
    private MaterialInfo steel;

    @BeforeEach
    void catalog() {
        central = directory.warehouse("Almacén Central", true);
        torre = directory.worksite("Torre Norte");
        cement = directory.material("CEM-001", true);
        steel = directory.material("ACE-012", true);
        directory.assign(ROSA.userId(), central.id());
        TenantContext.set(ADMIN);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ---------- Carga de existencias ----------

    @Test
    void the_assigned_manager_registers_an_entry_and_the_event_is_written() {
        TenantContext.set(ROSA);

        StockLevel level = service.registerEntry(central.id(), cement.id(), new BigDecimal("100"), "  Guía 001  ");

        assertThat(level.availableQty()).isEqualByComparingTo("100");
        StockEntryRegistered event = captured(InventoryEvents.STOCK_ENTRY_REGISTERED, StockEntryRegistered.class);
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.quantity()).isEqualByComparingTo("100");
        assertThat(event.availableAfter()).isEqualByComparingTo("100");
        assertThat(event.note()).isEqualTo("Guía 001");
        assertThat(event.entryType()).isEqualTo("INITIAL");
        assertThat(stock.movements).extracting(StockMovement::type).containsExactly(StockMovement.Type.INITIAL);
    }

    @Test
    void without_an_explicit_type_the_second_entry_is_a_replenishment() {
        service.registerEntry(central.id(), cement.id(), new BigDecimal("100"), null);
        StockLevel level = service.registerEntry(central.id(), cement.id(), new BigDecimal("40"), "Guía 002");

        assertThat(level.availableQty()).isEqualByComparingTo("140");
        assertThat(stock.movements).extracting(StockMovement::type)
                .containsExactly(StockMovement.Type.INITIAL, StockMovement.Type.REPLENISHMENT);
        assertThat(stock.movements.get(1).reference()).isEqualTo("Guía 002");
    }

    @Test
    void the_initial_stock_is_registered_only_once_and_a_replenishment_can_be_explicit() {
        service.registerEntry(central.id(), steel.id(), new BigDecimal("5"), null, EntryType.REPLENISHMENT);
        assertThat(stock.movements).extracting(StockMovement::type).containsExactly(StockMovement.Type.REPLENISHMENT);

        assertThatThrownBy(() -> service.registerEntry(central.id(), steel.id(), BigDecimal.TEN, null,
                EntryType.INITIAL))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INITIAL_STOCK_ALREADY_REGISTERED");
        service.registerEntry(central.id(), cement.id(), BigDecimal.TEN, "Apertura", EntryType.INITIAL);
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("10");
    }

    @Test
    void an_unassigned_manager_or_a_worksite_or_an_unknown_place_is_not_found() {
        Site callao = directory.warehouse("Almacén Callao", true);
        TenantContext.set(ROSA);
        assertThatThrownBy(() -> service.registerEntry(callao.id(), cement.id(), BigDecimal.TEN, null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "WAREHOUSE_NOT_FOUND");
        TenantContext.set(ADMIN);
        assertThatThrownBy(() -> service.registerEntry(torre.id(), cement.id(), BigDecimal.TEN, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.registerEntry(central.id(), UUID.randomUUID(), BigDecimal.TEN, null))
                .isInstanceOf(ResourceNotFoundException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_FOUND");
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void inactive_warehouses_and_retired_materials_do_not_receive_entries() {
        Site closed = directory.warehouse("Almacén Cerrado", false);
        MaterialInfo retired = directory.material("OLD-001", false);
        assertThatThrownBy(() -> service.registerEntry(closed.id(), cement.id(), BigDecimal.TEN, null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "SITE_INACTIVE");
        assertThatThrownBy(() -> service.registerEntry(central.id(), retired.id(), BigDecimal.TEN, null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "MATERIAL_INACTIVE");
        assertThatThrownBy(() -> service.registerEntry(central.id(), cement.id(), BigDecimal.ZERO, null))
                .isInstanceOf(ValidationException.class);
    }

    // ---------- Saga: reserva ----------

    @Test
    void an_approved_order_with_enough_stock_is_reserved_line_by_line() {
        stock.put(central.id(), cement.id(), "100", "0");
        stock.put(central.id(), steel.id(), "20", "0");
        UUID order = UUID.randomUUID();
        UUID line1 = UUID.randomUUID();
        UUID line2 = UUID.randomUUID();

        boolean reserved = service.reserveForOrder(new OrderApproved(order, central.id(), List.of(
                new OrderLine(line1, cement.id(), new BigDecimal("50")),
                new OrderLine(line2, steel.id(), new BigDecimal("20")))));

        assertThat(reserved).isTrue();
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("50");
        assertThat(item(central, cement).reservedQty()).isEqualByComparingTo("50");
        assertThat(item(central, steel).availableQty()).isZero();
        StockReserved event = captured(InventoryEvents.STOCK_RESERVED, StockReserved.class);
        assertThat(event.orderId()).isEqualTo(order);
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.reservations()).extracting(InventoryEvents.ReservedLine::orderLineId)
                .containsExactly(line1, line2);
        assertThat(event.reservations().get(0).quantity()).isEqualByComparingTo("50");
    }

    @Test
    void if_any_line_is_short_nothing_is_reserved_and_the_rejection_says_what_is_missing() {
        stock.put(central.id(), cement.id(), "100", "0");
        stock.put(central.id(), steel.id(), "5", "0");
        UUID order = UUID.randomUUID();
        UUID phantom = UUID.randomUUID();

        boolean reserved = service.reserveForOrder(new OrderApproved(order, central.id(), List.of(
                new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("50")),
                new OrderLine(UUID.randomUUID(), steel.id(), new BigDecimal("20")),
                new OrderLine(UUID.randomUUID(), phantom, new BigDecimal("1")))));

        assertThat(reserved).isFalse();
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("100");
        assertThat(stock.reservations()).isEmpty();
        StockReservationRejected event = captured(InventoryEvents.STOCK_RESERVATION_REJECTED,
                StockReservationRejected.class);
        assertThat(event.orderId()).isEqualTo(order);
        assertThat(event.missing()).extracting(InventoryEvents.MissingLine::materialId).containsExactly(steel.id(), phantom);
        assertThat(event.missing().get(0).requested()).isEqualByComparingTo("20");
        assertThat(event.missing().get(0).available()).isEqualByComparingTo("5");
        assertThat(event.missing().get(1).available()).isZero();
        assertThat(event.reason()).isEqualTo("Stock insuficiente en el almacén para ACE-012 (hay 5, se pidió 20), "
                + phantom + " (hay 0, se pidió 1)");
    }

    @Test
    void a_repeated_approval_confirms_the_existing_reservations_without_reserving_twice() {
        stock.put(central.id(), cement.id(), "100", "0");
        UUID order = UUID.randomUUID();
        OrderApproved approved = new OrderApproved(order, central.id(), List.of(
                new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("30"))));
        service.reserveForOrder(approved);

        assertThat(service.reserveForOrder(approved)).isTrue();

        assertThat(item(central, cement).reservedQty()).isEqualByComparingTo("30");
        assertThat(stock.reservations()).hasSize(1);
    }

    @Test
    void if_the_stock_vanishes_while_reserving_the_whole_step_fails_to_be_retried() {
        InMemoryStockRepository racing = new InMemoryStockRepository() {
            private int reads = -1;

            @Override
            public java.util.Optional<StockItem> find(UUID locationId, UUID materialId) {
                // La revisión ve 100; la reserva, ya sin stock (otra operación se lo llevó).
                return reads++ <= 0 ? super.find(locationId, materialId)
                        : java.util.Optional.of(new StockItem(UUID.randomUUID(), locationId, materialId,
                        BigDecimal.ONE, BigDecimal.ZERO, 9));
            }
        };
        racing.put(central.id(), cement.id(), "100", "0");
        StockCommandService racy = new StockCommandService(new StockOperations(racing, Clock.systemUTC()), racing,
                directory, outbox);

        assertThatThrownBy(() -> racy.reserveForOrder(new OrderApproved(UUID.randomUUID(), central.id(), List.of(
                new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("50"))))))
                .isInstanceOf(InsufficientStockException.class);
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    // ---------- Saga: salida del despacho ----------

    @Test
    void a_departed_dispatch_consumes_the_reservation_and_leaves_the_warehouse() {
        stock.put(central.id(), cement.id(), "100", "0");
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        service.reserveForOrder(new OrderApproved(order, central.id(), List.of(
                new OrderLine(line, cement.id(), new BigDecimal("50")))));
        UUID dispatch = UUID.randomUUID();

        service.deductForDispatch(new DispatchDeparted(dispatch, order, central.id(), "MAN-20261103-7KQ2M9XA",
                List.of(new OrderLine(line, cement.id(), new BigDecimal("30")))));

        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("50");
        assertThat(item(central, cement).reservedQty()).isEqualByComparingTo("20");
        StockDeducted event = captured(InventoryEvents.STOCK_DEDUCTED, StockDeducted.class);
        assertThat(event.dispatchId()).isEqualTo(dispatch);
        assertThat(event.orderId()).isEqualTo(order);
        assertThat(event.lines().get(0).quantity()).isEqualByComparingTo("30");
        assertThat(event.lines().get(0).reservedAfter()).isEqualByComparingTo("20");
        assertThat(stock.movements).extracting(StockMovement::reference).contains("Despacho MAN-20261103-7KQ2M9XA");
    }

    @Test
    void a_line_without_reservation_is_deducted_from_available_and_fails_only_if_it_is_not_enough() {
        stock.put(central.id(), cement.id(), "10", "0");
        UUID dispatch = UUID.randomUUID();

        service.deductForDispatch(new DispatchDeparted(dispatch, UUID.randomUUID(), central.id(), null,
                List.of(new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("4")))));
        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("6");
        assertThat(stock.movements).extracting(StockMovement::reference).contains("Despacho " + dispatch);

        assertThatThrownBy(() -> service.deductForDispatch(new DispatchDeparted(UUID.randomUUID(), UUID.randomUUID(),
                central.id(), null, List.of(new OrderLine(UUID.randomUUID(), cement.id(), new BigDecimal("7"))))))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void other_conflicts_while_consuming_are_not_hidden() {
        StockOperations failing = mock(StockOperations.class);
        org.mockito.Mockito.when(failing.consumeReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("STOCK_CONCURRENT_MODIFICATION", "cambió"));
        StockCommandService withFailure = new StockCommandService(failing, stock, directory, outbox);

        assertThatThrownBy(() -> withFailure.deductForDispatch(new DispatchDeparted(UUID.randomUUID(),
                UUID.randomUUID(), central.id(), "MAN", List.of(new OrderLine(UUID.randomUUID(), cement.id(),
                BigDecimal.ONE))))).hasFieldOrPropertyWithValue("code", "STOCK_CONCURRENT_MODIFICATION");
    }

    // ---------- Saga: recepción en obra ----------

    @Test
    void the_worksite_receives_only_the_delta_of_each_line() {
        UUID reception = UUID.randomUUID();
        UUID dispatch = UUID.randomUUID();

        service.receiveAtWorksite(new ReceptionReceived(reception, dispatch, torre.id(), InventoryEvents.SOURCE_PARTIAL,
                List.of(new ReceivedLine(cement.id(), new BigDecimal("20")), new ReceivedLine(steel.id(), BigDecimal.ZERO))));
        service.receiveAtWorksite(new ReceptionReceived(reception, dispatch, torre.id(),
                InventoryEvents.SOURCE_CONFIRMATION, List.of(new ReceivedLine(cement.id(), new BigDecimal("9.5")),
                new ReceivedLine(steel.id(), null))));

        assertThat(item(torre, cement).availableQty()).isEqualByComparingTo("29.5");
        assertThat(stock.find(torre.id(), steel.id())).isEmpty();
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(outbox, org.mockito.Mockito.times(2)).append(eq(InventoryEvents.STOCK_RECEIVED),
                eq(InventoryEvents.RECEPTION), eq(reception), payloads.capture());
        StockReceived last = (StockReceived) payloads.getAllValues().get(1);
        assertThat(last.source()).isEqualTo(InventoryEvents.SOURCE_CONFIRMATION);
        assertThat(last.worksiteId()).isEqualTo(torre.id());
        assertThat(last.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("9.5");
            assertThat(line.availableAfter()).isEqualByComparingTo("29.5");
        });
    }

    @Test
    void a_reception_without_new_quantities_changes_nothing_and_publishes_nothing() {
        service.receiveAtWorksite(new ReceptionReceived(UUID.randomUUID(), UUID.randomUUID(), torre.id(),
                InventoryEvents.SOURCE_CONFIRMATION, List.of(new ReceivedLine(cement.id(), BigDecimal.ZERO))));

        assertThat(stock.movements).isEmpty();
        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    // ---------- Saga: liberación ----------

    @Test
    void closing_the_order_releases_what_was_not_dispatched() {
        stock.put(central.id(), cement.id(), "100", "0");
        stock.put(central.id(), steel.id(), "10", "0");
        UUID order = UUID.randomUUID();
        UUID cementLine = UUID.randomUUID();
        UUID steelLine = UUID.randomUUID();
        service.reserveForOrder(new OrderApproved(order, central.id(), List.of(
                new OrderLine(cementLine, cement.id(), new BigDecimal("50")),
                new OrderLine(steelLine, steel.id(), new BigDecimal("10")))));
        service.deductForDispatch(new DispatchDeparted(UUID.randomUUID(), order, central.id(), null, List.of(
                new OrderLine(cementLine, cement.id(), new BigDecimal("30")),
                new OrderLine(steelLine, steel.id(), new BigDecimal("10")))));

        service.releaseForOrder(new OrderEnded(order, InventoryEvents.RELEASE_ORDER_CLOSED));

        assertThat(item(central, cement).availableQty()).isEqualByComparingTo("70");
        assertThat(item(central, cement).reservedQty()).isZero();
        assertThat(stock.reservations()).extracting(StockReservation::status)
                .containsExactlyInAnyOrder(StockReservation.Status.RELEASED, StockReservation.Status.CONSUMED);
        StockReleased event = captured(InventoryEvents.STOCK_RELEASED, StockReleased.class);
        assertThat(event.reason()).isEqualTo(InventoryEvents.RELEASE_ORDER_CLOSED);
        assertThat(event.warehouseId()).isEqualTo(central.id());
        assertThat(event.lines()).singleElement().satisfies(line -> {
            assertThat(line.orderLineId()).isEqualTo(cementLine);
            assertThat(line.quantity()).isEqualByComparingTo("20");
        });

        service.releaseForOrder(new OrderEnded(order, InventoryEvents.RELEASE_ORDER_CLOSED));
        verify(outbox, org.mockito.Mockito.times(1)).append(eq(InventoryEvents.STOCK_RELEASED), any(), any(), any());
    }

    @Test
    void an_order_without_reservations_releases_nothing() {
        service.releaseForOrder(new OrderEnded(UUID.randomUUID(), InventoryEvents.RELEASE_ORDER_REJECTED));

        verify(outbox, never()).append(anyString(), anyString(), any(), any());
    }

    private StockItem item(Site site, MaterialInfo material) {
        return stock.find(site.id(), material.id()).orElseThrow();
    }

    private <T> T captured(String eventType, Class<T> type) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq(eventType), anyString(), any(), payload.capture());
        return type.cast(payload.getValue());
    }
}
