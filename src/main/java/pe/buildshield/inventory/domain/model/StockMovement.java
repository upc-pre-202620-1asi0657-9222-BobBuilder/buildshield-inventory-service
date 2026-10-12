package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro inmutable de un movimiento de stock, con el saldo disponible que dejó.
 *
 * <p>Tipos: ENTRY (recepción en obra), DEDUCTION (descuento directo), RESERVE (disponible a reservado al
 * aprobar un pedido), RELEASE (reservado a disponible), DISPATCH (salida de lo reservado al despachar),
 * INITIAL (inventario inicial del almacén), REPLENISHMENT (reposición del almacén), ADJUSTMENT_IN y
 * ADJUSTMENT_OUT (ajuste por conteo de validación), RETURN_OUT (salida de la obra por devolución) y RETURN_IN
 * (reingreso al almacén de lo aceptado de una devolución).
 */
public record StockMovement(UUID stockItemId, Type type, BigDecimal quantity, BigDecimal balanceAfter, String reference,
        Instant occurredAt) {

    public enum Type {
        ENTRY, DEDUCTION, RESERVE, RELEASE, DISPATCH, INITIAL, REPLENISHMENT, ADJUSTMENT_IN, ADJUSTMENT_OUT,
        RETURN_OUT, RETURN_IN
    }

    public StockMovement {
        Objects.requireNonNull(stockItemId, "stockItemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (balanceAfter.signum() < 0) {
            throw new IllegalStateException("Un movimiento no puede dejar stock negativo");
        }
    }
}
