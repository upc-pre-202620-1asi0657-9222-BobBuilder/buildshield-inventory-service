package pe.buildshield.inventory.domain.model;

import java.util.List;

/** Página del historial de movimientos, del más reciente al más antiguo. */
public record MovementPage(List<RecordedMovement> items, int page, int size, long total) {
}
