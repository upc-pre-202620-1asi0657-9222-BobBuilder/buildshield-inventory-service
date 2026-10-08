package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro inmutable de un movimiento de stock, con el saldo disponible que dejó.
 *
 * <p>Tipos: ENTRY (entrada), DEDUCTION (descuento directo), RESERVE (disponible a reservado al aprobar
 * un pedido), RELEASE (reservado a disponible) y DISPATCH (salida de lo reservado al despachar).
 */
public record StockMovement(UUID stockItemId, Type type, BigDecimal quantity, BigDecimal balanceAfter, String reference,
        Instant occurredAt) {

    public enum Type { ENTRY, DEDUCTION, RESERVE, RELEASE, DISPATCH }

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
