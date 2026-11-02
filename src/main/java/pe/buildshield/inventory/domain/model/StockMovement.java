package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro inmutable de un movimiento de stock.
 *
 * <p>Tipos: ENTRY (recepción en obra), DEDUCTION (descuento directo), RESERVE (disponible a reservado al
 * aprobar un pedido), RELEASE (reservado a disponible), DISPATCH (salida de lo reservado al despachar),
 * INITIAL (inventario inicial del almacén), REPLENISHMENT (reposición del almacén), ADJUSTMENT_IN y
 * ADJUSTMENT_OUT (ajuste por conteo físico), RETURN_OUT (salida de la obra por devolución) y RETURN_IN
 * (reingreso al almacén de lo aceptado de una devolución).
 *
 * @param sourceRef lo que originó el movimiento: el despacho (DISPATCH, DEDUCTION y ENTRY, que por eso enlaza la
 *                  entrada en obra con el almacén de origen), el pedido (RESERVE y RELEASE), la devolución
 *                  (RETURN_OUT y RETURN_IN) o el ajuste (ADJUSTMENT_IN y ADJUSTMENT_OUT); nulo en las cargas de almacén
 */
public record StockMovement(UUID stockItemId, Type type, BigDecimal quantity, UUID sourceRef, Instant createdAt) {

    public enum Type {
        ENTRY, DEDUCTION, RESERVE, RELEASE, DISPATCH, INITIAL, REPLENISHMENT, ADJUSTMENT_IN, ADJUSTMENT_OUT,
        RETURN_OUT, RETURN_IN
    }

    public StockMovement {
        Objects.requireNonNull(stockItemId, "stockItemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
