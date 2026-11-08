package pe.buildshield.inventory.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtros opcionales del historial de movimientos (US40, T90). Todos pueden ser nulos.
 *
 * @param from desde (incluido)
 * @param to   hasta (excluido)
 */
public record MovementFilter(UUID materialId, StockMovement.Type type, Instant from, Instant to) {
}
