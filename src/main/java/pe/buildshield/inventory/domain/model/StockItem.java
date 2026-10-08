package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Existencias de un material en un almacén u obra. Lo disponible nunca es negativo; {@code version} se usa
 * para el bloqueo optimista del descuento.
 */
public record StockItem(UUID id, UUID locationId, UUID materialId, BigDecimal availableQty, BigDecimal reservedQty,
        long version) {

    public StockItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(materialId, "materialId");
        if (availableQty == null || availableQty.signum() < 0 || reservedQty == null || reservedQty.signum() < 0) {
            throw new IllegalStateException("El stock no puede ser negativo");
        }
    }

    public boolean hasAvailable(Quantity quantity) {
        return availableQty.compareTo(quantity.value()) >= 0;
    }

    public BigDecimal availableAfterDeducting(Quantity quantity) {
        return availableQty.subtract(quantity.value());
    }

    public boolean hasReserved(Quantity quantity) {
        return reservedQty.compareTo(quantity.value()) >= 0;
    }

    public BigDecimal reservedAfterReserving(Quantity quantity) {
        return reservedQty.add(quantity.value());
    }

    public BigDecimal reservedAfterConsuming(Quantity quantity) {
        return reservedQty.subtract(quantity.value());
    }

    /** Lo disponible después de devolver {@code quantity} de lo reservado (liberación de una reserva). */
    public BigDecimal availableAfterReleasing(Quantity quantity) {
        return availableQty.add(quantity.value());
    }
}
