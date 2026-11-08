package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Línea de un conteo de validación: lo contado y, desde que el conteo finaliza, lo que decía el sistema.
 *
 * @param countedQty nulo mientras no se registre lo contado
 * @param systemQty  nulo hasta finalizar el conteo
 */
public record StockCountLine(UUID materialId, BigDecimal countedQty, BigDecimal systemQty) {

    public StockCountLine {
        Objects.requireNonNull(materialId, "materialId");
    }

    /** Contado menos sistema: positivo si sobra material, negativo si falta; nulo antes de comparar. */
    public BigDecimal difference() {
        return countedQty == null || systemQty == null ? null : countedQty.subtract(systemQty);
    }

    public boolean hasDifference() {
        BigDecimal difference = difference();
        return difference != null && difference.signum() != 0;
    }
}
