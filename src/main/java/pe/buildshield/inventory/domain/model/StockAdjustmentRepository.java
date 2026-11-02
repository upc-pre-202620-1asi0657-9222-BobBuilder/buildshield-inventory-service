package pe.buildshield.inventory.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ajustes por conteo físico de la organización del contexto. */
public interface StockAdjustmentRepository {

    void add(StockAdjustment adjustment);

    Optional<StockAdjustment> find(UUID adjustmentId);

    /** Ajustes de los lugares indicados, del más reciente al más antiguo; {@code materialId} es opcional. */
    List<StockAdjustment> findByLocations(Collection<UUID> locationIds, UUID materialId);
}
