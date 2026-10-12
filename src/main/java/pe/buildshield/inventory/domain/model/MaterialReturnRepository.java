package pe.buildshield.inventory.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Devoluciones de saldos de la organización del contexto. */
public interface MaterialReturnRepository {

    /** Guarda una devolución nueva con sus líneas. */
    void add(MaterialReturn materialReturn);

    /**
     * Guarda el estado y las líneas solo si nadie cambió la devolución desde que se leyó (versión).
     *
     * @return {@code false} si otra operación la cambió antes (no hubo efecto)
     */
    boolean update(MaterialReturn materialReturn);

    Optional<MaterialReturn> find(UUID returnId);

    /**
     * Devoluciones cuya obra está en {@code worksiteIds} o cuyo almacén está en {@code warehouseIds}, de la más
     * reciente a la más antigua; {@code status} es opcional.
     */
    List<MaterialReturn> findByLocations(Collection<UUID> worksiteIds, Collection<UUID> warehouseIds,
            MaterialReturn.Status status);
}
