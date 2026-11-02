package pe.buildshield.inventory.support;

import pe.buildshield.inventory.domain.model.StockAdjustment;
import pe.buildshield.inventory.domain.model.StockAdjustmentRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ajustes por conteo físico en memoria (una sola organización). */
public class InMemoryStockAdjustmentRepository implements StockAdjustmentRepository {

    public final List<StockAdjustment> stored = new ArrayList<>();

    @Override
    public void add(StockAdjustment adjustment) {
        stored.add(adjustment);
    }

    @Override
    public Optional<StockAdjustment> find(UUID adjustmentId) {
        return stored.stream().filter(found -> found.id().equals(adjustmentId)).findFirst();
    }

    @Override
    public List<StockAdjustment> findByLocations(Collection<UUID> locationIds, UUID materialId) {
        List<StockAdjustment> found = new ArrayList<>(stored.stream()
                .filter(adjustment -> locationIds.contains(adjustment.locationId()))
                .filter(adjustment -> materialId == null || adjustment.materialId().equals(materialId))
                .toList());
        Collections.reverse(found);
        return found;
    }
}
