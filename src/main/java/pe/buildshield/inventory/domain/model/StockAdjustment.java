package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ajuste por conteo físico de una existencia (US41): lo contado frente a lo que tenía el sistema (disponible más
 * reservado, porque lo reservado sigue físicamente en el almacén). Se guarda aunque no haya diferencia, como constancia
 * del conteo.
 */
public record StockAdjustment(UUID id, UUID stockItemId, UUID locationId, UUID materialId, BigDecimal countedQty,
        BigDecimal systemQty, String reason, UUID createdBy, Instant createdAt) {

    public static final int REASON_MAX = 200;

    public StockAdjustment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stockItemId, "stockItemId");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(materialId, "materialId");
        Objects.requireNonNull(countedQty, "countedQty");
        Objects.requireNonNull(systemQty, "systemQty");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Contado menos sistema: positivo si sobra material, negativo si falta. */
    public BigDecimal difference() {
        return countedQty.subtract(systemQty);
    }

    /**
     * Lo contado de un material, con la escala de las cantidades.
     *
     * @throws ValidationException {@code INVALID_COUNTED_QUANTITY} si es negativo o tiene más de 3 decimales
     */
    public static BigDecimal countedQuantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > Quantity.SCALE) {
            throw new ValidationException("INVALID_COUNTED_QUANTITY",
                    "Lo contado debe ser cero o mayor, con hasta 3 decimales",
                    List.of(new ErrorDetail("countedQty", "cero o mayor, máximo 3 decimales")));
        }
        return value.setScale(Quantity.SCALE, RoundingMode.UNNECESSARY);
    }
}
