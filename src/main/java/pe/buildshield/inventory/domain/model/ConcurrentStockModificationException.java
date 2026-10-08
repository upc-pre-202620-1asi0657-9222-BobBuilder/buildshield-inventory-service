package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ConflictException;

import java.util.UUID;

/** El stock cambió en cada uno de los reintentos por otras operaciones simultáneas. Se responde 409. */
public class ConcurrentStockModificationException extends ConflictException {

    public ConcurrentStockModificationException(UUID locationId, UUID materialId, int attempts) {
        super("STOCK_CONCURRENT_MODIFICATION", "El stock del material " + materialId + " en " + locationId
                + " cambió durante la operación (" + attempts + " intentos); vuelve a intentarlo");
    }
}
