package pe.buildshield.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saldo de un material en un lugar después de una operación.
 *
 * @param stockItemId ítem afectado; solo se informa en las entradas (nulo en el resto)
 */
public record StockLevel(UUID locationId, UUID materialId, BigDecimal availableQty, BigDecimal reservedQty,
        UUID stockItemId) {

    public StockLevel(UUID locationId, UUID materialId, BigDecimal availableQty, BigDecimal reservedQty) {
        this(locationId, materialId, availableQty, reservedQty, null);
    }
}
