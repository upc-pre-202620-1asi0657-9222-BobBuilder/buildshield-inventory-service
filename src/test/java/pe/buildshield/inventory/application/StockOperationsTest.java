package pe.buildshield.inventory.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.domain.model.ConcurrentStockModificationException;
import pe.buildshield.inventory.domain.model.InsufficientStockException;

import pe.buildshield.inventory.domain.model.Quantity;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.shared.error.ConflictException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Descuento con bloqueo optimista: hasta 3 reintentos; sin stock, falla sin efectos. */
class StockOperationsTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();

    private final StockRepository stock = mock(StockRepository.class);
    private final StockOperations operations = new StockOperations(stock, Clock.fixed(NOW, ZoneOffset.UTC));

    private static StockItem item(String available, long version) {
        return new StockItem(ITEM, WAREHOUSE, CEMENT, new BigDecimal(available), BigDecimal.ZERO, version);
    }

    @Test
    void deducts_and_records_the_movement_with_the_new_balance() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", 4)));
        when(stock.tryDeduct(ITEM, 4, Quantity.of("7"))).thenReturn(true);

        StockLevel level = operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("7"), "despacho D-1");

        assertThat(level.availableQty()).isEqualByComparingTo("3");
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.DEDUCTION);
        assertThat(movement.getValue().balanceAfter()).isEqualByComparingTo("3");
        assertThat(movement.getValue().reference()).isEqualTo("despacho D-1");
        assertThat(movement.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void retries_after_a_version_conflict_and_succeeds() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", 4)), Optional.of(item("9", 5)));
        when(stock.tryDeduct(ITEM, 4, Quantity.of("7"))).thenReturn(false);
        when(stock.tryDeduct(ITEM, 5, Quantity.of("7"))).thenReturn(true);

        StockLevel level = operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("7"), null);

        assertThat(level.availableQty()).isEqualByComparingTo("2");
        verify(stock, times(1)).record(any());
    }

    @Test
    void fails_without_effects_when_a_concurrent_deduction_leaves_too_little() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", 4)), Optional.of(item("3", 5)));
        when(stock.tryDeduct(ITEM, 4, Quantity.of("7"))).thenReturn(false);

        assertThatThrownBy(() -> operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("7"), null))
                .isInstanceOf(InsufficientStockException.class)
                .hasFieldOrPropertyWithValue("code", InsufficientStockException.CODE)
                .satisfies(ex -> {
                    assertThat(((InsufficientStockException) ex).available()).isEqualByComparingTo("3");
                    assertThat(((InsufficientStockException) ex).requested()).isEqualByComparingTo("7");
                });
        verify(stock, never()).record(any());
    }

    @Test
    void gives_up_after_three_retries() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", 4)));
        when(stock.tryDeduct(eq(ITEM), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("1"), null))
                .isInstanceOf(ConcurrentStockModificationException.class)
                .hasFieldOrPropertyWithValue("code", "STOCK_CONCURRENT_MODIFICATION");
        verify(stock, times(StockOperations.MAX_RETRIES + 1)).tryDeduct(eq(ITEM), anyLong(), any());
        verify(stock, never()).record(any());
    }

    @Test
    void no_stock_record_means_insufficient_stock() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("1"), null))
                .isInstanceOf(InsufficientStockException.class);
        verify(stock, never()).tryDeduct(any(), anyLong(), any());
    }

    @Test
    void more_than_available_fails_before_trying_to_update() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("5", 1)));

        assertThatThrownBy(() -> operations.deduct(WAREHOUSE, CEMENT, new BigDecimal("5.001"), null))
                .isInstanceOf(InsufficientStockException.class);
        verify(stock, never()).tryDeduct(any(), anyLong(), any());
    }

    @Test
    void invalid_quantity_is_rejected() {
        assertThatThrownBy(() -> operations.deduct(WAREHOUSE, CEMENT, BigDecimal.ZERO, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> operations.add(WAREHOUSE, CEMENT, new BigDecimal("-3"), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void entry_adds_and_records_the_movement() {
        when(stock.add(WAREHOUSE, CEMENT, Quantity.of("100"))).thenReturn(item("120", 6));

        StockLevel level = operations.add(WAREHOUSE, CEMENT, new BigDecimal("100"), "Guía 001");

        assertThat(level.availableQty()).isEqualByComparingTo("120");
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.ENTRY);
        assertThat(movement.getValue().quantity()).isEqualByComparingTo("100");
        assertThat(movement.getValue().balanceAfter()).isEqualByComparingTo("120");
    }

    // ---------- Reservas (RF40) y consumo al despachar (RF41) ----------

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    private static StockItem item(String available, String reserved, long version) {
        return new StockItem(ITEM, WAREHOUSE, CEMENT, new BigDecimal(available), new BigDecimal(reserved), version);
    }

    private static StockReservation reservation(String quantity, String consumed) {
        return new StockReservation(UUID.randomUUID(), ITEM, WAREHOUSE, CEMENT, ORDER, LINE, new BigDecimal(quantity),
                new BigDecimal(consumed), StockReservation.Status.RESERVED, NOW);
    }

    @Test
    void reserves_moving_available_to_reserved_and_records_the_reservation() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "0", 4)));
        when(stock.tryReserve(ITEM, 4, Quantity.of("7"))).thenReturn(true);

        StockLevel level = operations.reserve(WAREHOUSE, CEMENT, new BigDecimal("7"), ORDER, LINE);

        assertThat(level.availableQty()).isEqualByComparingTo("3");
        assertThat(level.reservedQty()).isEqualByComparingTo("7");
        ArgumentCaptor<StockReservation> saved = ArgumentCaptor.forClass(StockReservation.class);
        verify(stock).saveReservation(saved.capture());
        assertThat(saved.getValue().orderLineId()).isEqualTo(LINE);
        assertThat(saved.getValue().remaining()).isEqualByComparingTo("7");
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.RESERVE);
        assertThat(movement.getValue().balanceAfter()).isEqualByComparingTo("3");
    }

    @Test
    void reservation_retries_on_version_conflict_and_fails_without_effects_when_stock_is_gone() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "0", 4)), Optional.of(item("3", "7", 5)));
        when(stock.tryReserve(ITEM, 4, Quantity.of("7"))).thenReturn(false);

        assertThatThrownBy(() -> operations.reserve(WAREHOUSE, CEMENT, new BigDecimal("7"), ORDER, LINE))
                .isInstanceOf(InsufficientStockException.class);
        verify(stock, never()).saveReservation(any());
        verify(stock, never()).record(any());
    }

    @Test
    void reservation_without_stock_record_or_with_permanent_conflicts_fails() {
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> operations.reserve(WAREHOUSE, CEMENT, BigDecimal.ONE, ORDER, LINE))
                .isInstanceOf(InsufficientStockException.class);

        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "0", 4)));
        when(stock.tryReserve(eq(ITEM), anyLong(), any())).thenReturn(false);
        assertThatThrownBy(() -> operations.reserve(WAREHOUSE, CEMENT, BigDecimal.ONE, ORDER, LINE))
                .isInstanceOf(ConcurrentStockModificationException.class);
    }

    @Test
    void consuming_a_reservation_takes_it_out_of_reserved_and_records_the_dispatch() {
        when(stock.findReservation(LINE)).thenReturn(Optional.of(reservation("50", "20")));
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "30", 8)));
        when(stock.tryConsumeReserved(ITEM, 8, Quantity.of("30"))).thenReturn(true);
        when(stock.tryConsumeReservation(LINE, Quantity.of("30"))).thenReturn(true);

        StockLevel level = operations.consumeReservation(WAREHOUSE, CEMENT, new BigDecimal("30"), LINE, "Despacho D-2");

        assertThat(level.availableQty()).isEqualByComparingTo("10");
        assertThat(level.reservedQty()).isZero();
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.DISPATCH);
        assertThat(movement.getValue().reference()).isEqualTo("Despacho D-2");
    }

    @Test
    void consuming_more_than_reserved_is_a_conflict_without_effects() {
        when(stock.findReservation(LINE)).thenReturn(Optional.of(reservation("50", "40")));
        assertThatThrownBy(() -> operations.consumeReservation(WAREHOUSE, CEMENT, new BigDecimal("11"), LINE, null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", StockOperations.RESERVATION_NOT_AVAILABLE);

        when(stock.findReservation(LINE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> operations.consumeReservation(WAREHOUSE, CEMENT, BigDecimal.ONE, LINE, null))
                .hasFieldOrPropertyWithValue("code", StockOperations.RESERVATION_NOT_AVAILABLE);

        when(stock.findReservation(LINE)).thenReturn(Optional.of(reservation("50", "0")));
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "0", 8)));
        assertThatThrownBy(() -> operations.consumeReservation(WAREHOUSE, CEMENT, BigDecimal.ONE, LINE, null))
                .hasFieldOrPropertyWithValue("code", StockOperations.RESERVATION_NOT_AVAILABLE);

        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "50", 8)));
        when(stock.tryConsumeReserved(ITEM, 8, Quantity.of("1"))).thenReturn(true);
        when(stock.tryConsumeReservation(LINE, Quantity.of("1"))).thenReturn(false);
        assertThatThrownBy(() -> operations.consumeReservation(WAREHOUSE, CEMENT, BigDecimal.ONE, LINE, null))
                .hasFieldOrPropertyWithValue("code", StockOperations.RESERVATION_NOT_AVAILABLE);
        verify(stock, never()).record(any());
    }

    @Test
    void consuming_gives_up_after_three_version_conflicts() {
        when(stock.findReservation(LINE)).thenReturn(Optional.of(reservation("50", "0")));
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "50", 8)));
        when(stock.tryConsumeReserved(eq(ITEM), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> operations.consumeReservation(WAREHOUSE, CEMENT, BigDecimal.ONE, LINE, null))
                .isInstanceOf(ConcurrentStockModificationException.class);
    }

    // ---------- Liberación de reservas (pedido rechazado o cerrado) ----------

    @Test
    void releasing_returns_what_remains_to_available_and_marks_the_reservation_released() {
        StockReservation reservation = reservation("50", "20");
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("10", "30", 8)));
        when(stock.tryRelease(ITEM, 8, Quantity.of("30"))).thenReturn(true);
        when(stock.tryMarkReleased(reservation.id())).thenReturn(true);

        StockLevel level = operations.release(reservation, "Liberación").orElseThrow();

        assertThat(level.availableQty()).isEqualByComparingTo("40");
        assertThat(level.reservedQty()).isZero();
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.RELEASE);
        assertThat(movement.getValue().quantity()).isEqualByComparingTo("30");
        assertThat(movement.getValue().balanceAfter()).isEqualByComparingTo("40");
    }

    @Test
    void releasing_a_consumed_or_released_reservation_does_nothing() {
        StockReservation consumed = new StockReservation(UUID.randomUUID(), ITEM, WAREHOUSE, CEMENT, ORDER, LINE,
                new BigDecimal("5"), new BigDecimal("5"), StockReservation.Status.CONSUMED, NOW);
        StockReservation released = new StockReservation(UUID.randomUUID(), ITEM, WAREHOUSE, CEMENT, ORDER, LINE,
                new BigDecimal("5"), BigDecimal.ZERO, StockReservation.Status.RELEASED, NOW);

        assertThat(operations.release(consumed, null)).isEmpty();
        assertThat(operations.release(released, null)).isEmpty();
        verify(stock, never()).tryRelease(any(), anyLong(), any());
    }

    @Test
    void releasing_retries_on_version_conflicts_and_gives_up_after_three() {
        StockReservation reservation = reservation("5", "0");
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("0", "5", 1)), Optional.of(item("0", "5", 2)));
        when(stock.tryRelease(ITEM, 1, Quantity.of("5"))).thenReturn(false);
        when(stock.tryRelease(ITEM, 2, Quantity.of("5"))).thenReturn(true);
        when(stock.tryMarkReleased(reservation.id())).thenReturn(true);
        assertThat(operations.release(reservation, null)).isPresent();

        when(stock.tryRelease(eq(ITEM), anyLong(), any())).thenReturn(false);
        assertThatThrownBy(() -> operations.release(reservation, null))
                .isInstanceOf(ConcurrentStockModificationException.class);
    }

    @Test
    void releasing_fails_if_another_release_won_or_the_item_is_missing() {
        StockReservation reservation = reservation("5", "0");
        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.of(item("0", "5", 1)));
        when(stock.tryRelease(ITEM, 1, Quantity.of("5"))).thenReturn(true);
        when(stock.tryMarkReleased(reservation.id())).thenReturn(false);
        assertThatThrownBy(() -> operations.release(reservation, null))
                .isInstanceOf(ConcurrentStockModificationException.class);

        when(stock.find(WAREHOUSE, CEMENT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> operations.release(reservation, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adding_creates_or_increases_the_item_and_reports_it() {
        when(stock.add(WAREHOUSE, CEMENT, Quantity.of("5"))).thenReturn(item("15", "0", 2));

        StockLevel level = operations.add(WAREHOUSE, CEMENT, new BigDecimal("5"), "Guía 7");

        assertThat(level.availableQty()).isEqualByComparingTo("15");
        assertThat(level.stockItemId()).isEqualTo(ITEM);
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stock).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(StockMovement.Type.ENTRY);
    }
}
