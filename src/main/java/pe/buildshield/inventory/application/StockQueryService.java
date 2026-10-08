package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.RecordedMovement;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Consultas de stock con la visibilidad de cada rol (la del Core):
 *
 * <ul>
 *   <li>Stock de almacenes: el administrador ve todos; el encargado de almacén, los suyos; el encargado de obra,
 *       los activos.</li>
 *   <li>Stock de obras (lo recibido): el administrador y el encargado de almacén ven todas; el encargado de obra,
 *       las suyas.</li>
 *   <li>Reservas: el administrador, todas; el encargado de almacén, las de sus almacenes.</li>
 *   <li>Movimientos: de los almacenes y obras que el usuario ve.</li>
 * </ul>
 *
 * Lo que el usuario no ve simplemente no aparece (las listas no responden 404).
 */
@Service
public class StockQueryService {

    public static final int MOVEMENTS_LIMIT = 200;
    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    static final String SITE_MANAGER = "SITE_MANAGER";

    private final StockRepository stock;
    private final SiteDirectory directory;

    public StockQueryService(StockRepository stock, SiteDirectory directory) {
        this.stock = stock;
        this.directory = directory;
    }

    /** Saldos por almacén y material; {@code warehouseIdFilter} opcional. */
    @Transactional(readOnly = true)
    public List<StockView> list(UUID warehouseIdFilter) {
        Map<UUID, Site> warehouses = visible(directory.locations(SiteDirectory.WAREHOUSE), canSeeWarehouse(),
                warehouseIdFilter);
        return views(warehouses, item -> new StockView(item.site().id(), item.site().name(), item.material().id(),
                item.material().sku(), item.material().name(), item.material().unit(), item.item().availableQty(),
                item.item().reservedQty()));
    }

    /** Saldos por obra y material (lo recibido en obra); {@code worksiteIdFilter} opcional. */
    @Transactional(readOnly = true)
    public List<WorksiteStockView> listWorksites(UUID worksiteIdFilter) {
        Map<UUID, Site> worksites = visible(directory.locations(SiteDirectory.WORKSITE), canSeeWorksite(),
                worksiteIdFilter);
        return views(worksites, item -> new WorksiteStockView(item.site().id(), item.site().name(),
                item.material().id(), item.material().sku(), item.material().name(), item.material().unit(),
                item.item().availableQty()));
    }

    /** Reservas de los almacenes visibles; filtros opcionales por pedido y almacén. */
    @Transactional(readOnly = true)
    public List<ReservationView> reservations(UUID orderId, UUID warehouseIdFilter) {
        Map<UUID, Site> warehouses = visible(directory.locations(SiteDirectory.WAREHOUSE), canSeeWarehouse(),
                warehouseIdFilter);
        if (warehouses.isEmpty()) {
            return List.of();
        }
        List<StockReservation> found = stock.findReservationsByLocations(warehouses.keySet()).stream()
                .filter(reservation -> orderId == null || reservation.orderId().equals(orderId))
                .toList();
        Map<UUID, MaterialInfo> materials = directory.findMaterials(found.stream()
                .map(StockReservation::materialId).collect(Collectors.toSet()));
        return found.stream().map(reservation -> {
            MaterialInfo material = materials.get(reservation.materialId());
            return new ReservationView(reservation.id(), reservation.orderId(), reservation.orderLineId(),
                    reservation.locationId(), warehouses.get(reservation.locationId()).name(), reservation.materialId(),
                    material == null ? null : material.sku(), material == null ? null : material.name(),
                    reservation.quantity(), reservation.consumedQty(), reservation.remaining(),
                    reservation.status().name(), reservation.reservedAt());
        }).toList();
    }

    /** Últimos movimientos de los lugares visibles; filtros opcionales por lugar y material. */
    @Transactional(readOnly = true)
    public List<MovementView> movements(UUID locationIdFilter, UUID materialId) {
        Map<UUID, Site> sites = new LinkedHashMap<>();
        sites.putAll(visible(directory.locations(SiteDirectory.WAREHOUSE), canSeeWarehouse(), locationIdFilter));
        sites.putAll(visible(directory.locations(SiteDirectory.WORKSITE), canSeeWorksite(), locationIdFilter));
        if (sites.isEmpty()) {
            return List.of();
        }
        List<RecordedMovement> found = stock.findMovements(sites.keySet(), materialId, MOVEMENTS_LIMIT);
        Map<UUID, MaterialInfo> materials = directory.findMaterials(found.stream()
                .map(RecordedMovement::materialId).collect(Collectors.toSet()));
        return found.stream().map(movement -> {
            Site site = sites.get(movement.locationId());
            MaterialInfo material = materials.get(movement.materialId());
            return new MovementView(movement.id(), movement.locationId(), site.type(), site.name(),
                    movement.materialId(), material == null ? null : material.sku(), movement.type().name(),
                    movement.quantity(), movement.balanceAfter(), movement.reference(), movement.occurredAt(),
                    movement.recordedBy());
        }).toList();
    }

    private Map<UUID, Site> visible(List<Site> sites, Predicate<Site> canSee, UUID filter) {
        return sites.stream()
                .filter(site -> filter == null || site.id().equals(filter))
                .filter(canSee)
                .collect(Collectors.toMap(Site::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private <V> List<V> views(Map<UUID, Site> sites, Function<VisibleItem, V> mapper) {
        if (sites.isEmpty()) {
            return List.of();
        }
        List<StockItem> items = stock.findByLocations(sites.keySet());
        Map<UUID, MaterialInfo> materials = directory.findMaterials(items.stream()
                .map(StockItem::materialId).collect(Collectors.toSet()));
        return items.stream()
                .map(item -> materials.containsKey(item.materialId())
                        ? new VisibleItem(item, sites.get(item.locationId()), materials.get(item.materialId())) : null)
                .filter(Objects::nonNull)
                .map(mapper)
                .toList();
    }

    private Predicate<Site> canSeeWarehouse() {
        TenantInfo requester = TenantContext.require();
        return switch (requester.role()) {
            case ADMINISTRATOR -> warehouse -> true;
            case WAREHOUSE_MANAGER -> {
                Set<UUID> assigned = directory.assignedLocations(requester.userId());
                yield warehouse -> assigned.contains(warehouse.id());
            }
            case SITE_MANAGER -> Site::active;
            default -> warehouse -> false;
        };
    }

    private Predicate<Site> canSeeWorksite() {
        TenantInfo requester = TenantContext.require();
        return switch (requester.role()) {
            case ADMINISTRATOR, WAREHOUSE_MANAGER -> worksite -> true;
            case SITE_MANAGER -> {
                Set<UUID> assigned = directory.assignedLocations(requester.userId());
                yield worksite -> assigned.contains(worksite.id());
            }
            default -> worksite -> false;
        };
    }

    private record VisibleItem(StockItem item, Site site, MaterialInfo material) {
    }

    /** Saldo de un material en un almacén (mismo formato que el Core). */
    public record StockView(UUID warehouseId, String warehouseName, UUID materialId, String sku, String materialName,
            String unit, BigDecimal availableQty, BigDecimal reservedQty) {
    }

    /** Saldo de un material en una obra. */
    public record WorksiteStockView(UUID worksiteId, String worksiteName, UUID materialId, String sku,
            String materialName, String unit, BigDecimal availableQty) {
    }

    /** Reserva de una línea de pedido. */
    public record ReservationView(UUID id, UUID orderId, UUID orderLineId, UUID warehouseId, String warehouseName,
            UUID materialId, String sku, String materialName, BigDecimal quantity, BigDecimal consumedQty,
            BigDecimal remainingQty, String status, Instant reservedAt) {
    }

    /** Movimiento de stock. */
    public record MovementView(UUID id, UUID locationId, String locationType, String locationName, UUID materialId,
            String sku, String type, BigDecimal quantity, BigDecimal balanceAfter, String reference,
            Instant occurredAt, UUID recordedBy) {
    }
}
