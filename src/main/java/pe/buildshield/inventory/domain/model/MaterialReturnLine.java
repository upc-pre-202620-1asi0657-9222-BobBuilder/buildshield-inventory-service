package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Línea del saldo que la obra devuelve: lo declarado y, cuando el almacén verifica lo recibido, lo aceptado. Lo
 * rechazado no se guarda: es lo declarado menos lo aceptado.
 *
 * @param detail          estado o detalle del material declarado por la obra (opcional)
 * @param acceptedQty     nulo hasta la verificación en el almacén
 * @param rejectionReason obligatorio si se rechazó algo
 */
public record MaterialReturnLine(UUID id, UUID materialId, BigDecimal quantity, String detail, BigDecimal acceptedQty,
        String rejectionReason) {

    public MaterialReturnLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(materialId, "materialId");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalStateException("Una línea de devolución tiene cantidad mayor que cero");
        }
        if (acceptedQty != null && acceptedQty.compareTo(quantity) > 0) {
            throw new IllegalStateException("Lo aceptado no puede superar lo devuelto");
        }
    }

    public boolean isVerified() {
        return acceptedQty != null;
    }

    /** Lo devuelto que el almacén no aceptó; nulo hasta la verificación. */
    public BigDecimal rejectedQty() {
        return acceptedQty == null ? null : quantity.subtract(acceptedQty);
    }

    public boolean hasRejection() {
        return acceptedQty != null && acceptedQty.compareTo(quantity) < 0;
    }
}
