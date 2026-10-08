package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Movimiento ya registrado, con su lugar, material y quién lo registró (consulta del historial). */
public record RecordedMovement(UUID id, UUID locationId, UUID materialId, StockMovement.Type type, BigDecimal quantity,
        BigDecimal balanceAfter, String reference, Instant occurredAt, UUID recordedBy) {
}
