package pe.buildshield.inventory.support;

import pe.buildshield.inventory.domain.model.MovementFilter;
import pe.buildshield.inventory.domain.model.MovementPage;
import pe.buildshield.inventory.domain.model.Quantity;
import pe.buildshield.inventory.domain.model.RecordedMovement;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock en memoria con la misma semántica que {@code JdbcStockRepository} (versión y cantidades condicionan cada
 * cambio), para probar los casos de uso sin base de datos. Una sola organización.
 */
public class InMemoryStockRepository implements StockRepository {

    private final Map<UUID, StockItem> items = new LinkedHashMap<>();
    private final Map<UUID, StockReservation> reservations = new LinkedHashMap<>();
    public final List<StockMovement> movements = new ArrayList<>();

    public StockItem put(UUID locationId, UUID materialId, String available, String reserved) {
        StockItem existing = find(locationId, materialId).orElse(null);
        StockItem item = new StockItem(existing == null ? UUID.randomUUID() : existing.id(), locationId, materialId,
                scale(new BigDecimal(available)), scale(new BigDecimal(reserved)),
                existing == null ? 0 : existing.version() + 1);
        items.put(item.id(), item);
        return item;
    }

    public Collection<StockReservation> reservations() {
        return reservations.values();
    }

    @Override
    public Optional<StockItem> find(UUID locationId, UUID materialId) {
        return items.values().stream()
                .filter(item -> item.locationId().equals(locationId) && item.materialId().equals(materialId))
                .findFirst();
    }

    @Override
    public List<StockItem> findByLocations(Collection<UUID> locationIds) {
        return items.values().stream().filter(item -> locationIds.contains(item.locationId()))
                .sorted(Comparator.comparing((StockItem item) -> item.locationId().toString())
                        .thenComparing(item -> item.materialId().toString()))
                .toList();
    }

    @Override
    public boolean tryDeduct(UUID stockItemId, long expectedVersion, Quantity quantity) {
        return change(stockItemId, expectedVersion, quantity.value().negate(), BigDecimal.ZERO);
    }

    @Override
    public boolean tryReserve(UUID stockItemId, long expectedVersion, Quantity quantity) {
        return change(stockItemId, expectedVersion, quantity.value().negate(), quantity.value());
    }

    @Override
    public boolean tryConsumeReserved(UUID stockItemId, long expectedVersion, Quantity quantity) {
        return change(stockItemId, expectedVersion, BigDecimal.ZERO, quantity.value().negate());
    }

    @Override
    public boolean tryRelease(UUID stockItemId, long expectedVersion, Quantity quantity) {
        return change(stockItemId, expectedVersion, quantity.value(), quantity.value().negate());
    }

    private boolean change(UUID id, long expectedVersion, BigDecimal availableDelta, BigDecimal reservedDelta) {
        StockItem item = items.get(id);
        if (item == null || item.version() != expectedVersion) {
            return false;
        }
        BigDecimal available = item.availableQty().add(availableDelta);
        BigDecimal reserved = item.reservedQty().add(reservedDelta);
        if (available.signum() < 0 || reserved.signum() < 0) {
            return false;
        }
        items.put(id, new StockItem(id, item.locationId(), item.materialId(), available, reserved, item.version() + 1));
        return true;
    }

    @Override
    public StockItem add(UUID locationId, UUID materialId, Quantity quantity) {
        StockItem item = find(locationId, materialId).orElse(null);
        StockItem updated = item == null
                ? new StockItem(UUID.randomUUID(), locationId, materialId, quantity.value(), scale(BigDecimal.ZERO), 0)
                : new StockItem(item.id(), locationId, materialId, item.availableQty().add(quantity.value()),
                item.reservedQty(), item.version() + 1);
        items.put(updated.id(), updated);
        return updated;
    }

    @Override
    public void record(StockMovement movement) {
        movements.add(movement);
    }

    @Override
    public List<RecordedMovement> findMovements(Collection<UUID> locationIds, UUID materialId, int limit) {
        List<RecordedMovement> found = new ArrayList<>();
        for (int i = movements.size() - 1; i >= 0 && found.size() < limit; i--) {
            StockMovement movement = movements.get(i);
            StockItem item = items.get(movement.stockItemId());
            if (locationIds.contains(item.locationId()) && (materialId == null || item.materialId().equals(materialId))) {
                found.add(new RecordedMovement(UUID.randomUUID(), item.locationId(), item.materialId(), movement.type(),
                        movement.quantity(), movement.balanceAfter(), movement.reference(), movement.occurredAt(),
                        UUID.randomUUID()));
            }
        }
        return found;
    }

    @Override
    public MovementPage findMovementPage(Collection<UUID> locationIds, MovementFilter filter, int page, int size) {
        List<RecordedMovement> matching = findMovements(locationIds, filter.materialId(), Integer.MAX_VALUE).stream()
                .filter(movement -> filter.type() == null || movement.type() == filter.type())
                .filter(movement -> filter.from() == null || !movement.occurredAt().isBefore(filter.from()))
                .filter(movement -> filter.to() == null || movement.occurredAt().isBefore(filter.to()))
                .toList();
        List<RecordedMovement> items = matching.stream().skip((long) page * size).limit(size).toList();
        return new MovementPage(items, page, size, matching.size());
    }

    @Override
    public void saveReservation(StockReservation reservation) {
        if (findReservation(reservation.orderLineId()).isPresent()) {
            throw new IllegalStateException("Ya hay una reserva para la línea " + reservation.orderLineId());
        }
        reservations.put(reservation.id(), reservation);
    }

    @Override
    public Optional<StockReservation> findReservation(UUID orderLineId) {
        return reservations.values().stream().filter(found -> found.orderLineId().equals(orderLineId)).findFirst();
    }

    @Override
    public List<StockReservation> findReservationsByOrder(UUID orderId) {
        return reservations.values().stream().filter(found -> found.orderId().equals(orderId)).toList();
    }

    @Override
    public List<StockReservation> findReservationsByLocations(Collection<UUID> locationIds) {
        List<StockReservation> found = new ArrayList<>(reservations.values().stream()
                .filter(reservation -> locationIds.contains(reservation.locationId())).toList());
        java.util.Collections.reverse(found);
        return found;
    }

    @Override
    public boolean tryConsumeReservation(UUID orderLineId, Quantity quantity) {
        StockReservation reservation = findReservation(orderLineId).orElse(null);
        if (reservation == null || !reservation.canConsume(quantity)) {
            return false;
        }
        BigDecimal consumed = reservation.consumedQty().add(quantity.value());
        reservations.put(reservation.id(), new StockReservation(reservation.id(), reservation.stockItemId(),
                reservation.locationId(), reservation.materialId(), reservation.orderId(), reservation.orderLineId(),
                reservation.quantity(), consumed, consumed.compareTo(reservation.quantity()) == 0
                ? StockReservation.Status.CONSUMED : StockReservation.Status.RESERVED, reservation.reservedAt()));
        return true;
    }

    @Override
    public boolean tryMarkReleased(UUID reservationId) {
        StockReservation reservation = reservations.get(reservationId);
        if (reservation == null || reservation.status() != StockReservation.Status.RESERVED) {
            return false;
        }
        reservations.put(reservationId, new StockReservation(reservation.id(), reservation.stockItemId(),
                reservation.locationId(), reservation.materialId(), reservation.orderId(), reservation.orderLineId(),
                reservation.quantity(), reservation.consumedQty(), StockReservation.Status.RELEASED,
                reservation.reservedAt()));
        return true;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(Quantity.SCALE, RoundingMode.UNNECESSARY);
    }
}
