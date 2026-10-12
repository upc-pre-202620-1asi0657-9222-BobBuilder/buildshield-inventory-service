package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Línea del saldo que la obra devuelve: lo declarado y, cuando el almacén verifica lo recibido, lo aceptado y lo
 * rechazado con su motivo. Lo aceptado más lo rechazado siempre es lo declarado.
 *
 * @param detail          estado o detalle del material declarado por la obra (opcional)
 * @param acceptedQty     nulo hasta la verificación en el almacén
 * @param rejectedQty     nulo hasta la verificación en el almacén
 * @param rejectionReason obligatorio si se rechazó algo
 */
public record MaterialReturnLine(UUID id, UUID materialId, BigDecimal quantity, String detail, BigDecimal acceptedQty,
        BigDecimal rejectedQty, String rejectionReason) {

    public MaterialReturnLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(materialId, "materialId");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalStateException("Una línea de devolución tiene cantidad mayor que cero");
        }
        if ((acceptedQty == null) != (rejectedQty == null)
                || acceptedQty != null && acceptedQty.add(rejectedQty).compareTo(quantity) != 0) {
            throw new IllegalStateException("Lo aceptado más lo rechazado debe ser lo devuelto");
        }
    }

    public boolean isVerified() {
        return acceptedQty != null;
    }

    public boolean hasRejection() {
        return rejectedQty != null && rejectedQty.signum() > 0;
    }
}
