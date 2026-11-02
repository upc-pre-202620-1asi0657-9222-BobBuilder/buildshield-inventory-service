package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.application.StockAdjustmentCommandService.CountedResult;
import pe.buildshield.inventory.domain.model.StockAdjustment;
import pe.buildshield.inventory.domain.model.StockAdjustmentRepository;
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
 * Consultas de los ajustes por conteo físico (US41). El administrador ve los de todos los almacenes; el encargado de
 * almacén, los de sus almacenes. Lo que el usuario no ve no aparece (y su detalle responde 404).
 */
@Service
public class StockAdjustmentQueryService {

    private final StockAdjustmentRepository adjustments;
    private final SiteDirectory directory;

    public StockAdjustmentQueryService(StockAdjustmentRepository adjustments, SiteDirectory directory) {
        this.adjustments = adjustments;
        this.directory = directory;
    }

    /** Ajustes de los almacenes visibles, del más reciente al más antiguo; filtros opcionales. */
    @Transactional(readOnly = true)
    public List<AdjustmentView> list(UUID warehouseIdFilter, UUID materialId) {
        Map<UUID, Site> warehouses = visibleWarehouses().stream()
                .filter(site -> warehouseIdFilter == null || site.id().equals(warehouseIdFilter))
                .collect(Collectors.toMap(Site::id, Function.identity()));
        if (warehouses.isEmpty()) {
            return List.of();
        }
        List<StockAdjustment> found = adjustments.findByLocations(warehouses.keySet(), materialId);
        Map<UUID, MaterialInfo> materials = directory.findMaterials(found.stream()
                .map(StockAdjustment::materialId).collect(Collectors.toSet()));
        return found.stream()
                .map(adjustment -> view(adjustment, warehouses.get(adjustment.locationId()), materials)).toList();
    }

    /** Detalle de un ajuste visible. */
    @Transactional(readOnly = true)
    public AdjustmentView get(UUID adjustmentId) {
        StockAdjustment adjustment = adjustments.find(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("COUNT_NOT_FOUND", "El conteo no existe"));
        Site warehouse = visibleWarehouses().stream().filter(site -> site.id().equals(adjustment.locationId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("COUNT_NOT_FOUND", "El conteo no existe"));
        return view(adjustment, warehouse, directory.findMaterials(List.of(adjustment.materialId())));
    }

    /** Vista del resultado de comparar o de aplicar un conteo. */
    @Transactional(readOnly = true)
    public CountResultView view(CountedResult result) {
        String warehouseName = directory.findLocation(result.warehouseId()).map(Site::name).orElse(null);
        Map<UUID, MaterialInfo> materials = directory.findMaterials(result.lines().stream()
                .map(StockAdjustmentCommandService.ResultLine::materialId).collect(Collectors.toSet()));
        List<CountLineView> lines = result.lines().stream().map(line -> {
            MaterialInfo material = materials.get(line.materialId());
            return new CountLineView(line.adjustmentId(), line.materialId(), material == null ? null : material.sku(),
                    material == null ? null : material.name(), material == null ? null : material.unit(),
                    line.countedQty(), line.systemQty(), line.difference());
        }).toList();
        long differences = lines.stream().filter(line -> line.difference().signum() != 0).count();
        return new CountResultView(result.warehouseId(), warehouseName, result.status(), result.reason(),
                (int) differences, lines);
    }

    private List<Site> visibleWarehouses() {
        TenantInfo requester = TenantContext.require();
        List<Site> all = directory.locations(SiteDirectory.WAREHOUSE);
        if (StockAdjustmentCommandService.ADMINISTRATOR.equals(requester.role())) {
            return all;
        }
        if (StockAdjustmentCommandService.WAREHOUSE_MANAGER.equals(requester.role())) {
            Set<UUID> assigned = directory.assignedLocations(requester.userId());
            return all.stream().filter(site -> assigned.contains(site.id())).toList();
        }
        return List.of();
    }

    private static AdjustmentView view(StockAdjustment adjustment, Site warehouse, Map<UUID, MaterialInfo> materials) {
        MaterialInfo material = materials.get(adjustment.materialId());
        return new AdjustmentView(adjustment.id(), adjustment.locationId(), warehouse.name(), adjustment.materialId(),
                material == null ? null : material.sku(), material == null ? null : material.name(),
                material == null ? null : material.unit(), adjustment.countedQty(), adjustment.systemQty(),
                adjustment.difference(), adjustment.reason(), adjustment.createdBy(), adjustment.createdAt());
    }

    /** Ajuste guardado de un material. {@code difference} = contado menos sistema. */
    public record AdjustmentView(UUID id, UUID warehouseId, String warehouseName, UUID materialId, String sku,
            String materialName, String unit, BigDecimal countedQty, BigDecimal systemQty, BigDecimal difference,
            String reason, UUID createdBy, Instant createdAt) {
    }

    /** Resultado de un conteo: MATCHED, DIFFERENCES_FOUND (solo al comparar) o ADJUSTED. */
    public record CountResultView(UUID warehouseId, String warehouseName, String status, String reason,
            int differenceCount, List<CountLineView> lines) {
    }

    /** {@code adjustmentId} es nulo si la línea no se guardó (al comparar). */
    public record CountLineView(UUID adjustmentId, UUID materialId, String sku, String materialName, String unit,
            BigDecimal countedQty, BigDecimal systemQty, BigDecimal difference) {
    }
}
