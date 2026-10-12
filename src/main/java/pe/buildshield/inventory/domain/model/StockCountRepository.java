package pe.buildshield.inventory.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Conteos de validación de la organización del contexto. */
public interface StockCountRepository {

    /** Guarda un conteo nuevo con sus líneas. */
    void add(StockCount count);

    /**
     * Guarda el estado y las líneas del conteo solo si nadie lo cambió desde que se leyó (versión).
     *
     * @return {@code false} si otra operación lo cambió antes (no hubo efecto)
     */
    boolean update(StockCount count);

    Optional<StockCount> find(UUID countId);

    /** ¿El almacén tiene un conteo abierto (en curso, con diferencias o revisado)? */
    boolean hasOpenCount(UUID warehouseId);

    /** Conteos de los almacenes indicados, del más reciente al más antiguo; {@code status} es opcional. */
    List<StockCount> findByWarehouses(Collection<UUID> warehouseIds, StockCount.Status status);
}
