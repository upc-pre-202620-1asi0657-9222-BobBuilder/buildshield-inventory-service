package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import pe.buildshield.inventory.domain.model.ConcurrentStockModificationException;
import pe.buildshield.inventory.domain.model.InsufficientStockException;
import pe.buildshield.inventory.domain.model.Quantity;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Entradas, descuentos, reservas, consumos y liberaciones de stock (QAS02: el inventario nunca queda negativo).
 * Portado de {@code StockOperations} del Core, sin la auditoría local: la auditoría consume los eventos.
 *
 * <p>Cada operación lee el ítem y ejecuta un UPDATE condicionado por la versión leída y por la cantidad
 * disponible o reservada. Si no actualiza nada, vuelve a leer: si ya no alcanza, falla con
 * {@link InsufficientStockException}; si alcanza, fue un conflicto de versión y reintenta, hasta
 * {@value #MAX_RETRIES} veces. El movimiento se registra solo si el UPDATE tuvo efecto.
 *
 * <p>No abre transacciones: se une a la de quien llama ({@link StockCommandService}).
 */
@Service
public class StockOperations {

    public static final int MAX_RETRIES = 3;
    public static final String RESERVATION_NOT_AVAILABLE = "RESERVATION_NOT_AVAILABLE";

    private final StockRepository stock;
    private final Clock clock;

    public StockOperations(StockRepository stock, Clock clock) {
        this.stock = stock;
        this.clock = clock;
    }

    /**
     * Descuento directo de lo disponible (sin reserva).
     *
     * @throws InsufficientStockException          si no alcanza; no modifica nada
     * @throws ConcurrentStockModificationException si se agotaron los reintentos
     */
    public StockLevel deduct(UUID locationId, UUID materialId, BigDecimal requested, String reference) {
        return deduct(locationId, materialId, requested, StockMovement.Type.DEDUCTION, reference);
    }

    /**
     * Descuento directo de lo disponible registrado con el tipo indicado (por ejemplo, ajuste por conteo o salida
     * de la obra por devolución).
     *
     * @throws InsufficientStockException          si no alcanza; no modifica nada
     * @throws ConcurrentStockModificationException si se agotaron los reintentos
     */
    public StockLevel deduct(UUID locationId, UUID materialId, BigDecimal requested, StockMovement.Type type,
            String reference) {
        Quantity quantity = new Quantity(requested);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            Optional<StockItem> current = stock.find(locationId, materialId);
            StockItem item = current.orElseThrow(() ->
                    new InsufficientStockException(locationId, materialId, BigDecimal.ZERO, quantity.value()));
            if (!item.hasAvailable(quantity)) {
                throw new InsufficientStockException(locationId, materialId, item.availableQty(), quantity.value());
            }
            if (stock.tryDeduct(item.id(), item.version(), quantity)) {
                BigDecimal balance = item.availableAfterDeducting(quantity);
                stock.record(new StockMovement(item.id(), type, quantity.value(), balance,
                        reference, clock.instant()));
                return new StockLevel(locationId, materialId, balance, item.reservedQty());
            }
        }
        throw new ConcurrentStockModificationException(locationId, materialId, MAX_RETRIES + 1);
    }

    /**
     * RF40: reserva {@code requested} para una línea de pedido aprobada; pasa de disponible a reservado.
     *
     * @throws InsufficientStockException          si no hay disponible suficiente; no modifica nada
     * @throws ConcurrentStockModificationException si se agotaron los reintentos
     */
    public StockLevel reserve(UUID locationId, UUID materialId, BigDecimal requested, UUID orderId, UUID orderLineId) {
        Quantity quantity = new Quantity(requested);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            StockItem item = stock.find(locationId, materialId).orElseThrow(() ->
                    new InsufficientStockException(locationId, materialId, BigDecimal.ZERO, quantity.value()));
            if (!item.hasAvailable(quantity)) {
                throw new InsufficientStockException(locationId, materialId, item.availableQty(), quantity.value());
            }
            if (stock.tryReserve(item.id(), item.version(), quantity)) {
                BigDecimal available = item.availableAfterDeducting(quantity);
                BigDecimal reserved = item.reservedAfterReserving(quantity);
                stock.saveReservation(StockReservation.reserve(item, orderId, orderLineId, quantity, clock.instant()));
                stock.record(new StockMovement(item.id(), StockMovement.Type.RESERVE, quantity.value(), available,
                        "Pedido " + orderId, clock.instant()));
                return new StockLevel(locationId, materialId, available, reserved);
            }
        }
        throw new ConcurrentStockModificationException(locationId, materialId, MAX_RETRIES + 1);
    }

    /**
     * RF41: consume {@code requested} de la reserva de la línea de pedido; sale de lo reservado del almacén.
     *
     * @throws ConflictException {@code RESERVATION_NOT_AVAILABLE} si la línea no tiene reserva suficiente
     */
    public StockLevel consumeReservation(UUID locationId, UUID materialId, BigDecimal requested, UUID orderLineId,
            String reference) {
        Quantity quantity = new Quantity(requested);
        StockReservation reservation = stock.findReservation(orderLineId)
                .filter(found -> found.canConsume(quantity))
                .orElseThrow(() -> reservationNotAvailable(orderLineId, quantity));
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            StockItem item = stock.find(locationId, materialId)
                    .filter(found -> found.id().equals(reservation.stockItemId()))
                    .orElseThrow(() -> reservationNotAvailable(orderLineId, quantity));
            if (!item.hasReserved(quantity)) {
                throw reservationNotAvailable(orderLineId, quantity);
            }
            if (stock.tryConsumeReserved(item.id(), item.version(), quantity)) {
                if (!stock.tryConsumeReservation(orderLineId, quantity)) {
                    throw reservationNotAvailable(orderLineId, quantity);
                }
                BigDecimal reserved = item.reservedAfterConsuming(quantity);
                stock.record(new StockMovement(item.id(), StockMovement.Type.DISPATCH, quantity.value(),
                        item.availableQty(), reference, clock.instant()));
                return new StockLevel(locationId, materialId, item.availableQty(), reserved);
            }
        }
        throw new ConcurrentStockModificationException(locationId, materialId, MAX_RETRIES + 1);
    }

    /**
     * Libera lo que queda sin consumir de una reserva vigente: vuelve de reservado a disponible y la reserva
     * pasa a RELEASED.
     *
     * @return el saldo después de liberar, o vacío si la reserva ya no estaba vigente o no le quedaba nada
     */
    public Optional<StockLevel> release(StockReservation reservation, String reference) {
        if (!reservation.isReleasable()) {
            return Optional.empty();
        }
        Quantity quantity = new Quantity(reservation.remaining());
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            StockItem item = stock.find(reservation.locationId(), reservation.materialId())
                    .filter(found -> found.id().equals(reservation.stockItemId()))
                    .orElseThrow(() -> new IllegalStateException("La reserva " + reservation.id() + " no tiene ítem"));
            if (stock.tryRelease(item.id(), item.version(), quantity)) {
                if (!stock.tryMarkReleased(reservation.id())) {
                    throw new ConcurrentStockModificationException(reservation.locationId(), reservation.materialId(),
                            attempt + 1);
                }
                BigDecimal available = item.availableAfterReleasing(quantity);
                stock.record(new StockMovement(item.id(), StockMovement.Type.RELEASE, quantity.value(), available,
                        reference, clock.instant()));
                return Optional.of(new StockLevel(reservation.locationId(), reservation.materialId(), available,
                        item.reservedAfterConsuming(quantity)));
            }
        }
        throw new ConcurrentStockModificationException(reservation.locationId(), reservation.materialId(),
                MAX_RETRIES + 1);
    }

    /** Suma a lo disponible del lugar (almacén u obra) como ENTRY; crea el ítem si no existía. */
    public StockLevel add(UUID locationId, UUID materialId, BigDecimal added, String reference) {
        return add(locationId, materialId, added, StockMovement.Type.ENTRY, reference);
    }

    /**
     * Suma a lo disponible del lugar registrando el movimiento con el tipo indicado (inventario inicial,
     * reposición, ajuste o reingreso por devolución); crea el ítem si no existía.
     */
    public StockLevel add(UUID locationId, UUID materialId, BigDecimal added, StockMovement.Type type,
            String reference) {
        Quantity quantity = new Quantity(added);
        StockItem item = stock.add(locationId, materialId, quantity);
        stock.record(new StockMovement(item.id(), type, quantity.value(), item.availableQty(),
                reference, clock.instant()));
        return new StockLevel(locationId, materialId, item.availableQty(), item.reservedQty(), item.id());
    }

    private static ConflictException reservationNotAvailable(UUID orderLineId, Quantity quantity) {
        return new ConflictException(RESERVATION_NOT_AVAILABLE,
                "La línea de pedido no tiene reservado lo que se intenta despachar",
                List.of(new ErrorDetail("orderLineId", orderLineId.toString()),
                        new ErrorDetail("quantity", quantity.value().toPlainString())));
    }
}
