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
 * copia y la actualización exige que el estado siga siendo el leído. Una sola organización.
 */
public class InMemoryMaterialReturnRepository implements MaterialReturnRepository {

    private final Map<UUID, MaterialReturn> returns = new HashMap<>();

    @Override
    public void add(MaterialReturn materialReturn) {
        returns.put(materialReturn.id(), materialReturn);
    }

    @Override
    public boolean update(MaterialReturn materialReturn) {
        MaterialReturn current = returns.get(materialReturn.id());
        if (current == null || current.status() != materialReturn.persistedStatus()) {
            return false;
        }
        returns.put(materialReturn.id(), materialReturn);
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
        return new MaterialReturn(source.id(), source.worksiteId(), source.warehouseId(), source.status(),
                source.registeredBy(), source.registeredAt(), source.lines());
    }
}
