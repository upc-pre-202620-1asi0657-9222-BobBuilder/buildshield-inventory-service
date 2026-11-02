package pe.buildshield.inventory.support;

import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturnRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Devoluciones en memoria con la misma semántica que {@code JdbcMaterialReturnRepository}: cada lectura devuelve una
 * copia y la actualización exige la versión leída. Una sola organización.
 */
public class InMemoryMaterialReturnRepository implements MaterialReturnRepository {

    private final Map<UUID, MaterialReturn> returns = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();

    @Override
    public void add(MaterialReturn materialReturn) {
        returns.put(materialReturn.id(), materialReturn);
        versions.put(materialReturn.id(), 0L);
    }

    @Override
    public boolean update(MaterialReturn materialReturn) {
        Long current = versions.get(materialReturn.id());
        if (current == null || current != materialReturn.version()) {
            return false;
        }
        returns.put(materialReturn.id(), materialReturn);
        versions.put(materialReturn.id(), current + 1);
        return true;
    }

    @Override
    public Optional<MaterialReturn> find(UUID returnId) {
        return Optional.ofNullable(returns.get(returnId)).map(this::copy);
    }

    @Override
    public List<MaterialReturn> findByLocations(Collection<UUID> worksiteIds, Collection<UUID> warehouseIds,
            MaterialReturn.Status status) {
        List<MaterialReturn> found = new ArrayList<>(returns.values().stream()
                .filter(materialReturn -> worksiteIds.contains(materialReturn.worksiteId())
                        || warehouseIds.contains(materialReturn.warehouseId()))
                .filter(materialReturn -> status == null || materialReturn.status() == status)
                .map(this::copy)
                .toList());
        found.sort(Comparator.comparing(MaterialReturn::registeredAt).reversed());
        return found;
    }

    private MaterialReturn copy(MaterialReturn source) {
        MaterialReturn copy = new MaterialReturn(source.id(), source.worksiteId(), source.warehouseId(),
                source.status(), source.note(), source.registeredBy(), source.registeredAt(), source.lines(),
                versions.get(source.id()));
        copy.restore(source.requestedBy(), source.requestedAt(), source.decidedBy(), source.decidedAt(),
                source.rejectionReason(), source.dispatchedBy(), source.dispatchedAt(), source.receivedBy(),
                source.receivedAt(), source.cancelledBy(), source.cancelledAt());
        return copy;
    }
}
