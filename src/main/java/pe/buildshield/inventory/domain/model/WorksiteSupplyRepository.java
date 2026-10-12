package pe.buildshield.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * De qué almacén recibió cada obra cada material (lo alimentan las recepciones). Sirve para devolver el saldo al
 * almacén de origen (US43).
 */
public interface WorksiteSupplyRepository {

    /** Suma {@code quantity} a lo que la obra recibió de ese material desde ese almacén. */
    void recordSupply(UUID worksiteId, UUID materialId, UUID warehouseId, BigDecimal quantity);

    /** Almacenes desde los que la obra recibió cada material de {@code materialIds}. */
    Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds);
}
