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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stock en memoria con la misma semántica que {@code JdbcStockRepository} (versión y cantidades condicionan cada
 * cambio), para probar los casos de uso sin base de datos. Una sola organización.
 */
public class InMemoryStockRepository implements StockRepository {

    private final Map<UUID, StockItem> items = new LinkedHashMap<>();
    private final Map<UUID, StockReservation> reservations = new LinkedHashMap<>();
    private final Map<List<UUID>, Set<UUID>> supplied = new HashMap<>();
    public final List<StockMovement> movements = new ArrayList<>();

    /** La obra recibió el material desde ese almacén (sin cargar movimientos en la prueba). */
    public void supplied(UUID worksiteId, UUID materialId, UUID warehouseId) {
        supplied.computeIfAbsent(List.of(worksiteId, materialId), ignored -> new HashSet<>()).add(warehouseId);
    }

    @Override
    public Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds) {
        Map<UUID, Set<UUID>> origins = new HashMap<>();
        for (UUID materialId : materialIds) {
            Set<UUID> warehouses = new HashSet<>(supplied.getOrDefault(List.of(worksiteId, materialId), Set.of()));
            for (StockMovement entry : movements) {
                StockItem received = items.get(entry.stockItemId());
                if (entry.type() != StockMovement.Type.ENTRY || entry.sourceRef() == null
                        || !received.locationId().equals(worksiteId) || !received.materialId().equals(materialId)) {
                    continue;
                }
                movements.stream()
                        .filter(departure -> entry.sourceRef().equals(departure.sourceRef())
                                && (departure.type() == StockMovement.Type.DISPATCH
                                || departure.type() == StockMovement.Type.DEDUCTION))
                        .map(departure -> items.get(departure.stockItemId()))
                        .filter(origin -> origin.materialId().equals(materialId))
                        .forEach(origin -> warehouses.add(origin.locationId()));
            }
            if (!warehouses.isEmpty()) {
                origins.put(materialId, warehouses);
            }
        }
        return origins;
    }

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
                        movement.quantity(), movement.balanceAfter(), movement.reference(), movement.createdAt(),
                        UUID.randomUUID()));
            }
        }
        return found;
    }

    @Override
    public MovementPage findMovementPage(Collection<UUID> locationIds, MovementFilter filter, int page, int size) {
        List<RecordedMovement> matching = findMovements(locationIds, filter.materialId(), Integer.MAX_VALUE).stream()
                .filter(movement -> filter.type() == null || movement.type() == filter.type())
                .filter(movement -> filter.from() == null || !movement.createdAt().isBefore(filter.from()))
                .filter(movement -> filter.to() == null || movement.createdAt().isBefore(filter.to()))
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
        return reservations.values().stream().filter(found -> found.orderLineId().equals(orderLineId)
                && found.status() == StockReservation.Status.RESERVED).findFirst();
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
        BigDecimal left = reservation.quantity().subtract(quantity.value());
        if (left.signum() == 0) {
            reservations.put(reservation.id(), withStatus(reservation, reservation.quantity(),
                    StockReservation.Status.CONSUMED));
        } else {
            reservations.put(reservation.id(), withStatus(reservation, left, StockReservation.Status.RESERVED));
            UUID consumedId = UUID.randomUUID();
            reservations.put(consumedId, new StockReservation(consumedId, reservation.stockItemId(),
                    reservation.locationId(), reservation.materialId(), reservation.orderId(),
                    reservation.orderLineId(), quantity.value(), StockReservation.Status.CONSUMED,
                    reservation.createdAt()));
        }
        return true;
    }

    @Override
    public boolean tryMarkReleased(UUID reservationId) {
        StockReservation reservation = reservations.get(reservationId);
        if (reservation == null || reservation.status() != StockReservation.Status.RESERVED) {
            return false;
        }
        reservations.put(reservationId, withStatus(reservation, reservation.quantity(),
                StockReservation.Status.RELEASED));
        return true;
    }

    private static StockReservation withStatus(StockReservation source, BigDecimal quantity,
            StockReservation.Status status) {
        return new StockReservation(source.id(), source.stockItemId(), source.locationId(), source.materialId(),
                source.orderId(), source.orderLineId(), quantity, status, source.createdAt());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(Quantity.SCALE, RoundingMode.UNNECESSARY);
    }
}
