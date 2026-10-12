package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturnLine;
import pe.buildshield.inventory.domain.model.MaterialReturnRepository;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.WorksiteSupplyRepository;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static pe.buildshield.inventory.application.MaterialReturnCommandService.ADMINISTRATOR;
import static pe.buildshield.inventory.application.MaterialReturnCommandService.SITE_MANAGER;
import static pe.buildshield.inventory.application.MaterialReturnCommandService.WAREHOUSE_MANAGER;

/**
 * Consultas del saldo de las obras y de sus devoluciones (US43).
 *
 * <ul>
 *   <li>Devoluciones: el administrador ve todas; el encargado de almacén, las dirigidas a sus almacenes; el
 *       encargado de obra, las de sus obras.</li>
 *   <li>Saldo de una obra: lo disponible por material, lo comprometido en devoluciones pendientes, lo que aún se
 *       puede devolver y los almacenes de origen. Lo ven el administrador, el encargado de almacén y el encargado
 *       asignado a la obra (la misma visibilidad del stock de obras).</li>
 * </ul>
 */
@Service
public class MaterialReturnQueryService {

    private final MaterialReturnRepository returns;
    private final WorksiteSupplyRepository supplies;
    private final StockRepository stock;
    private final SiteDirectory directory;

    public MaterialReturnQueryService(MaterialReturnRepository returns, WorksiteSupplyRepository supplies,
            StockRepository stock, SiteDirectory directory) {
        this.returns = returns;
        this.supplies = supplies;
        this.stock = stock;
        this.directory = directory;
    }

    /** Devoluciones visibles, de la más reciente a la más antigua; filtros opcionales. */
    @Transactional(readOnly = true)
    public List<ReturnView> list(MaterialReturn.Status status, UUID worksiteId, UUID warehouseId) {
        TenantInfo requester = TenantContext.require();
        List<UUID> worksites = switch (requester.role()) {
            case ADMINISTRATOR -> ids(directory.locations(SiteDirectory.WORKSITE));
            case SITE_MANAGER -> assigned(requester, SiteDirectory.WORKSITE);
            default -> List.of();
        };
        List<UUID> warehouses = switch (requester.role()) {
            case ADMINISTRATOR -> ids(directory.locations(SiteDirectory.WAREHOUSE));
            case WAREHOUSE_MANAGER -> assigned(requester, SiteDirectory.WAREHOUSE);
            default -> List.of();
        };
        if (worksites.isEmpty() && warehouses.isEmpty()) {
            return List.of();
        }
        List<MaterialReturn> found = returns.findByLocations(worksites, warehouses, status).stream()
                .filter(materialReturn -> worksiteId == null || materialReturn.worksiteId().equals(worksiteId))
                .filter(materialReturn -> warehouseId == null || materialReturn.warehouseId().equals(warehouseId))
                .toList();
        return views(found);
    }

    /** Detalle de una devolución visible. */
    @Transactional(readOnly = true)
    public ReturnView get(UUID returnId) {
        MaterialReturn found = returns.find(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("RETURN_NOT_FOUND", "La devolución no existe"));
        return view(found);
    }

    /** Vista de una devolución visible (también para responder después de cada comando). */
    public ReturnView view(MaterialReturn materialReturn) {
        TenantInfo requester = TenantContext.require();
        boolean visible = switch (requester.role()) {
            case ADMINISTRATOR -> true;
            case SITE_MANAGER -> directory.isAssigned(requester.userId(), materialReturn.worksiteId());
            case WAREHOUSE_MANAGER -> directory.isAssigned(requester.userId(), materialReturn.warehouseId());
            default -> false;
        };
        if (!visible) {
            throw new ResourceNotFoundException("RETURN_NOT_FOUND", "La devolución no existe");
        }
        return views(List.of(materialReturn)).get(0);
    }

    /**
     * Saldo restante de una obra: por cada material con stock disponible, lo comprometido en devoluciones pendientes,
     * lo que todavía se puede devolver y los almacenes que se lo despacharon.
     */
    @Transactional(readOnly = true)
    public WorksiteBalanceView balance(UUID worksiteId) {
        TenantInfo requester = TenantContext.require();
        Site worksite = directory.findLocation(worksiteId)
                .filter(Site::isWorksite)
                .filter(found -> switch (requester.role()) {
                    case ADMINISTRATOR, WAREHOUSE_MANAGER -> true;
                    case SITE_MANAGER -> directory.isAssigned(requester.userId(), found.id());
                    default -> false;
                })
                .orElseThrow(() -> new ResourceNotFoundException("WORKSITE_NOT_FOUND", "La obra no existe"));
        List<StockItem> items = stock.findByLocations(List.of(worksiteId)).stream()
                .filter(item -> item.availableQty().signum() > 0).toList();
        Map<UUID, BigDecimal> pending = new HashMap<>();
        for (MaterialReturn materialReturn : returns.findByLocations(List.of(worksiteId), List.of(), null)) {
            if (MaterialReturn.PENDING.contains(materialReturn.status())) {
                materialReturn.lines().forEach(line -> pending.merge(line.materialId(), line.quantity(), BigDecimal::add));
            }
        }
        Set<UUID> materialIds = items.stream().map(StockItem::materialId).collect(Collectors.toSet());
        Map<UUID, MaterialInfo> materials = directory.findMaterials(materialIds);
        Map<UUID, Set<UUID>> origins = supplies.originWarehouses(worksiteId, materialIds);
        Map<UUID, String> names = new HashMap<>();
        List<BalanceLineView> lines = items.stream()
                .filter(item -> materials.containsKey(item.materialId()))
                .map(item -> {
                    MaterialInfo material = materials.get(item.materialId());
                    BigDecimal committed = pending.getOrDefault(item.materialId(), BigDecimal.ZERO);
                    List<OriginView> from = origins.getOrDefault(item.materialId(), Set.of()).stream()
                            .map(id -> new OriginView(id, name(id, names)))
                            .sorted(Comparator.comparing(OriginView::warehouseName,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                            .toList();
                    return new BalanceLineView(item.materialId(), material.sku(), material.name(), material.unit(),
                            item.availableQty(), committed, item.availableQty().subtract(committed).max(BigDecimal.ZERO),
                            from);
                })
                .sorted(Comparator.comparing(BalanceLineView::sku))
                .toList();
        return new WorksiteBalanceView(worksite.id(), worksite.name(), lines);
    }

    private List<ReturnView> views(List<MaterialReturn> found) {
        Map<UUID, MaterialInfo> materials = directory.findMaterials(found.stream()
                .flatMap(materialReturn -> materialReturn.lines().stream()).map(MaterialReturnLine::materialId)
                .collect(Collectors.toSet()));
        Map<UUID, String> names = new HashMap<>();
        return found.stream().map(materialReturn -> new ReturnView(materialReturn.id(), materialReturn.worksiteId(),
                name(materialReturn.worksiteId(), names), materialReturn.warehouseId(),
                name(materialReturn.warehouseId(), names), materialReturn.status().name(), materialReturn.note(),
                materialReturn.hasRejections(), materialReturn.registeredBy(), materialReturn.registeredAt(),
                materialReturn.requestedBy(), materialReturn.requestedAt(), materialReturn.decidedBy(),
                materialReturn.decidedAt(), materialReturn.rejectionReason(), materialReturn.dispatchedBy(),
                materialReturn.dispatchedAt(), materialReturn.receivedBy(), materialReturn.receivedAt(),
                materialReturn.cancelledAt(), materialReturn.lines().stream().map(line -> {
                    MaterialInfo material = materials.get(line.materialId());
                    return new ReturnLineView(line.id(), line.materialId(), material == null ? null : material.sku(),
                            material == null ? null : material.name(), material == null ? null : material.unit(),
                            line.quantity(), line.detail(), line.acceptedQty(), line.rejectedQty(),
                            line.rejectionReason());
                }).toList())).toList();
    }

    private String name(UUID locationId, Map<UUID, String> cache) {
        return cache.computeIfAbsent(locationId, id -> directory.findLocation(id).map(Site::name).orElse(null));
    }

    private List<UUID> assigned(TenantInfo requester, String type) {
        Set<UUID> assigned = directory.assignedLocations(requester.userId());
        return directory.locations(type).stream().map(Site::id).filter(assigned::contains).toList();
    }

    private static List<UUID> ids(Collection<Site> sites) {
        return sites.stream().map(Site::id).toList();
    }

    /** Devolución de saldos con sus líneas. */
    public record ReturnView(UUID id, UUID worksiteId, String worksiteName, UUID warehouseId, String warehouseName,
            String status, String note, boolean hasRejections, UUID registeredBy, Instant registeredAt,
            UUID requestedBy, Instant requestedAt, UUID decidedBy, Instant decidedAt, String rejectionReason,
            UUID dispatchedBy, Instant dispatchedAt, UUID receivedBy, Instant receivedAt, Instant cancelledAt,
            List<ReturnLineView> lines) {
    }

    /** Lo aceptado y lo rechazado (con su motivo) solo desde la verificación del almacén. */
    public record ReturnLineView(UUID id, UUID materialId, String sku, String materialName, String unit,
            BigDecimal quantity, String detail, BigDecimal acceptedQty, BigDecimal rejectedQty,
            String rejectionReason) {
    }

    /** Saldo restante de una obra. */
    public record WorksiteBalanceView(UUID worksiteId, String worksiteName, List<BalanceLineView> lines) {
    }

    /** {@code returnableQty} = disponible menos lo comprometido en devoluciones pendientes. */
    public record BalanceLineView(UUID materialId, String sku, String materialName, String unit,
            BigDecimal availableQty, BigDecimal pendingReturnQty, BigDecimal returnableQty,
            List<OriginView> originWarehouses) {
    }

    /** Almacén que despachó el material a la obra. */
    public record OriginView(UUID warehouseId, String warehouseName) {
    }
}
