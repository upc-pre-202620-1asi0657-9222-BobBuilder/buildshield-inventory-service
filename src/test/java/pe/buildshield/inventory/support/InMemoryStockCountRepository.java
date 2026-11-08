package pe.buildshield.inventory.support;

import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockCountRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conteos en memoria con la misma semántica que {@code JdbcStockCountRepository}: cada lectura devuelve una copia y
 * la actualización exige la versión leída. Una sola organización.
 */
public class InMemoryStockCountRepository implements StockCountRepository {

    private final Map<UUID, StockCount> counts = new HashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();

    @Override
    public void add(StockCount count) {
        counts.put(count.id(), count);
        versions.put(count.id(), 0L);
    }

    @Override
    public boolean update(StockCount count) {
        Long current = versions.get(count.id());
        if (current == null || current != count.version()) {
            return false;
        }
        counts.put(count.id(), count);
        versions.put(count.id(), current + 1);
        return true;
    }

    @Override
    public Optional<StockCount> find(UUID countId) {
        return Optional.ofNullable(counts.get(countId)).map(this::copy);
    }

    @Override
    public boolean hasOpenCount(UUID warehouseId) {
        return counts.values().stream()
                .anyMatch(count -> count.warehouseId().equals(warehouseId) && StockCount.OPEN.contains(count.status()));
    }

    @Override
    public List<StockCount> findByWarehouses(Collection<UUID> warehouseIds, StockCount.Status status) {
        List<StockCount> found = new ArrayList<>(counts.values().stream()
                .filter(count -> warehouseIds.contains(count.warehouseId()))
                .filter(count -> status == null || count.status() == status)
                .map(this::copy)
                .toList());
        found.sort(Comparator.comparing(StockCount::startedAt).reversed());
        return found;
    }

    private StockCount copy(StockCount count) {
        StockCount copy = new StockCount(count.id(), count.warehouseId(), count.status(), count.note(),
                count.startedBy(), count.startedAt(), count.lines(), versions.get(count.id()));
        copy.restore(count.finishedBy(), count.finishedAt(), count.reviewNote(), count.reviewedBy(),
                count.reviewedAt(), count.adjustmentReason(), count.adjustedBy(), count.adjustedAt(),
                count.cancelledBy(), count.cancelledAt());
        return copy;
    }
}
