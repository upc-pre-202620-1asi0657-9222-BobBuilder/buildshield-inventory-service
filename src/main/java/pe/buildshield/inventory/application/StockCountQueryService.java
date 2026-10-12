package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockCountLine;
import pe.buildshield.inventory.domain.model.StockCountRepository;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultas de los conteos de validación (US41). El administrador ve los de todos los almacenes; el encargado de
 * almacén, los de sus almacenes. Lo que el usuario no ve no aparece (y su detalle responde 404).
 */
@Service
public class StockCountQueryService {

    private final StockCountRepository counts;
    private final SiteDirectory directory;

    public StockCountQueryService(StockCountRepository counts, SiteDirectory directory) {
        this.counts = counts;
        this.directory = directory;
    }

    /** Conteos de los almacenes visibles, del más reciente al más antiguo; filtros opcionales. */
    @Transactional(readOnly = true)
    public List<CountView> list(UUID warehouseIdFilter, StockCount.Status status) {
        Map<UUID, Site> warehouses = visibleWarehouses().stream()
                .filter(site -> warehouseIdFilter == null || site.id().equals(warehouseIdFilter))
                .collect(Collectors.toMap(Site::id, Function.identity()));
        if (warehouses.isEmpty()) {
            return List.of();
        }
        List<StockCount> found = counts.findByWarehouses(warehouses.keySet(), status);
        Map<UUID, MaterialInfo> materials = directory.findMaterials(found.stream()
                .flatMap(count -> count.lines().stream()).map(StockCountLine::materialId).collect(Collectors.toSet()));
        return found.stream().map(count -> view(count, warehouses.get(count.warehouseId()), materials)).toList();
    }

    /** Detalle de un conteo visible. */
    @Transactional(readOnly = true)
    public CountView get(UUID countId) {
        StockCount count = counts.find(countId)
                .orElseThrow(() -> new ResourceNotFoundException("COUNT_NOT_FOUND", "El conteo no existe"));
        return view(count);
    }

    /** Vista de un conteo (también para responder después de cada comando). */
    public CountView view(StockCount count) {
        Site warehouse = visibleWarehouses().stream().filter(site -> site.id().equals(count.warehouseId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("COUNT_NOT_FOUND", "El conteo no existe"));
        return view(count, warehouse, directory.findMaterials(count.lines().stream()
                .map(StockCountLine::materialId).collect(Collectors.toSet())));
    }

    private List<Site> visibleWarehouses() {
        TenantInfo requester = TenantContext.require();
        List<Site> all = directory.locations(SiteDirectory.WAREHOUSE);
        if (StockCountCommandService.ADMINISTRATOR.equals(requester.role())) {
            return all;
        }
        if (StockCountCommandService.WAREHOUSE_MANAGER.equals(requester.role())) {
            Set<UUID> assigned = directory.assignedLocations(requester.userId());
            return all.stream().filter(site -> assigned.contains(site.id())).toList();
        }
        return List.of();
    }

    private static CountView view(StockCount count, Site warehouse, Map<UUID, MaterialInfo> materials) {
        List<CountLineView> lines = count.lines().stream().map(line -> {
            MaterialInfo material = materials.get(line.materialId());
            return new CountLineView(line.materialId(), material == null ? null : material.sku(),
                    material == null ? null : material.name(), material == null ? null : material.unit(),
                    line.countedQty(), line.systemQty(), line.difference());
        }).toList();
        return new CountView(count.id(), count.warehouseId(), warehouse.name(), count.status().name(), count.note(),
                count.startedBy(), count.startedAt(), count.finishedAt(), count.differences().size(),
                count.reviewNote(), count.reviewedBy(), count.reviewedAt(), count.adjustmentReason(),
                count.adjustedBy(), count.adjustedAt(), count.cancelledAt(), lines);
    }

    /** Conteo de validación con sus líneas. */
    public record CountView(UUID id, UUID warehouseId, String warehouseName, String status, String note,
            UUID startedBy, Instant startedAt, Instant finishedAt, int differenceCount, String reviewNote,
            UUID reviewedBy, Instant reviewedAt, String adjustmentReason, UUID adjustedBy, Instant adjustedAt,
            Instant cancelledAt, List<CountLineView> lines) {
    }

    /** {@code difference} = contado menos sistema (nulo antes de finalizar). */
    public record CountLineView(UUID materialId, String sku, String materialName, String unit, BigDecimal countedQty,
            BigDecimal systemQty, BigDecimal difference) {
    }
}
