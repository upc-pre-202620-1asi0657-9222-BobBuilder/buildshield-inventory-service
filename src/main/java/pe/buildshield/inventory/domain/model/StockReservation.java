package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserva del stock de una línea de pedido aprobada (RF40, saga de reserva). Lo reservado sale del disponible del
 * almacén y se consume, total o parcialmente, cuando parte cada despacho (RF41). Lo que no se consumió se libera cuando
 * el pedido se rechaza o se cierra.
 *
 * <p>Una línea puede tener varias filas: una RESERVED con lo que queda y una CONSUMED por cada consumo parcial, además
 * de la RELEASED con lo liberado. Lo reservado para la línea es la suma de sus filas.
 */
public record StockReservation(UUID id, UUID stockItemId, UUID locationId, UUID materialId, UUID orderId,
        UUID orderLineId, BigDecimal quantity, Status status, Instant createdAt) {

    public enum Status { RESERVED, CONSUMED, RELEASED }

    public StockReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stockItemId, "stockItemId");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(materialId, "materialId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(orderLineId, "orderLineId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (quantity.signum() <= 0) {
            throw new IllegalStateException("Una reserva tiene cantidad mayor que cero");
        }
    }

    /** Nueva reserva de toda la cantidad, sin consumir. */
    public static StockReservation reserve(StockItem item, UUID orderId, UUID orderLineId, Quantity quantity,
            Instant now) {
        return new StockReservation(TimeOrderedId.next(), item.id(), item.locationId(), item.materialId(), orderId,
                orderLineId, quantity.value(), Status.RESERVED, now);
    }

    /** ¿Queda reservado al menos {@code amount} en esta fila? */
    public boolean canConsume(Quantity amount) {
        return status == Status.RESERVED && quantity.compareTo(amount.value()) >= 0;
    }

    /** ¿Sigue vigente, con algo que se pueda liberar? */
    public boolean isReleasable() {
        return status == Status.RESERVED;
    }
}
