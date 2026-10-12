package pe.buildshield.inventory.support;

import pe.buildshield.inventory.domain.model.WorksiteSupplyRepository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Origen de lo recibido en cada obra, en memoria (una sola organización). */
public class InMemoryWorksiteSupplyRepository implements WorksiteSupplyRepository {

    /** Clave: obra, material y almacén; valor: lo recibido. */
    public final Map<List<UUID>, BigDecimal> received = new HashMap<>();

    @Override
    public void recordSupply(UUID worksiteId, UUID materialId, UUID warehouseId, BigDecimal quantity) {
        received.merge(List.of(worksiteId, materialId, warehouseId), quantity, BigDecimal::add);
    }

    @Override
    public Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds) {
        Map<UUID, Set<UUID>> origins = new HashMap<>();
        received.keySet().stream()
                .filter(key -> key.get(0).equals(worksiteId) && materialIds.contains(key.get(1)))
                .forEach(key -> origins.computeIfAbsent(key.get(1), ignored -> new HashSet<>()).add(key.get(2)));
        return origins;
    }
}
