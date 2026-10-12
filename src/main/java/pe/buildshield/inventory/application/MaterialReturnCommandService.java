package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.InventoryEvents.ReturnedLine;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturn.DeclaredLine;
import pe.buildshield.inventory.domain.model.MaterialReturn.Verification;
import pe.buildshield.inventory.domain.model.MaterialReturnLine;
import pe.buildshield.inventory.domain.model.MaterialReturnRepository;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.WorksiteSupplyRepository;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ForbiddenException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registro de saldos de obra y su devolución al almacén de origen (US43, T92).
 *
 * <ul>
 *   <li>Lado de la obra (administrador o encargado asignado a la obra): registrar y detallar el saldo, solicitar la
 *       devolución, iniciar el retorno (el material sale de la obra, movimiento RETURN_OUT) y anular lo que aún no
 *       se aprobó.</li>
 *   <li>Lado del almacén de origen (administrador o encargado asignado al almacén): aprobar o rechazar la
 *       solicitud con su motivo y verificar lo recibido; solo lo aceptado vuelve al stock del almacén
 *       (RETURN_IN) y lo no aceptado queda registrado con su motivo.</li>
 * </ul>
 *
 * El almacén de origen es el que despachó el material a la obra (se anota al recibir cada despacho). Cada cambio
 * de estado publica su evento en la misma transacción (Transactional Outbox).
 */
@Service
public class MaterialReturnCommandService {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    static final String SITE_MANAGER = "SITE_MANAGER";

    private final MaterialReturnRepository returns;
    private final WorksiteSupplyRepository supplies;
    private final StockRepository stock;
    private final StockOperations operations;
    private final SiteDirectory directory;
    private final OutboxWriter outbox;
    private final Clock clock;

    public MaterialReturnCommandService(MaterialReturnRepository returns, WorksiteSupplyRepository supplies,
            StockRepository stock, StockOperations operations, SiteDirectory directory, OutboxWriter outbox,
            Clock clock) {
        this.returns = returns;
        this.supplies = supplies;
        this.stock = stock;
        this.operations = operations;
        this.directory = directory;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * AC01: el encargado registra y detalla el saldo que quedó sin usar en la obra.
     *
     * @param warehouseId almacén de origen; si es nulo se usa el único almacén que despachó esos materiales a la obra
     * @throws ConflictException {@code ORIGIN_WAREHOUSE_UNKNOWN}, {@code NOT_SUPPLIED_BY_WAREHOUSE},
     *                           {@code NO_COMMON_ORIGIN_WAREHOUSE}, {@code SITE_INACTIVE} o
     *                           {@code RETURN_EXCEEDS_BALANCE}
     */
    @Transactional
    public MaterialReturn register(UUID worksiteId, UUID warehouseId, List<DeclaredLine> lines, String note) {
        TenantInfo requester = TenantContext.require();
        directory.findLocation(worksiteId)
                .filter(Site::isWorksite)
                .filter(found -> worksiteSide(requester, found.id()))
                .orElseThrow(() -> new ResourceNotFoundException("WORKSITE_NOT_FOUND", "La obra no existe"));
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("RETURN_WITHOUT_LINES", "Detalla al menos un material del saldo");
        }
        Set<UUID> materialIds = new LinkedHashSet<>(lines.stream().map(DeclaredLine::materialId).toList());
        Map<UUID, MaterialInfo> known = directory.findMaterials(materialIds);
        for (UUID materialId : materialIds) {
            if (!known.containsKey(materialId)) {
                throw new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material no existe",
                        List.of(new ErrorDetail("materialId", String.valueOf(materialId))));
            }
        }
        UUID origin = originWarehouse(warehouseId, materialIds, supplies.originWarehouses(worksiteId, materialIds));
        Site warehouse = directory.findLocation(origin)
                .filter(Site::isWarehouse)
                .orElseThrow(() -> new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "El almacén no existe"));
        if (!warehouse.active()) {
            throw new ConflictException("SITE_INACTIVE", "El almacén de origen está desactivado");
        }
        MaterialReturn created = MaterialReturn.register(worksiteId, origin, lines, note, requester.userId(),
                clock.instant());
        requireBalance(created);
        returns.add(created);
        return created;
    }

    /** AC01: con el saldo registrado, la obra solicita la devolución. */
    @Transactional
    public MaterialReturn request(UUID returnId) {
        MaterialReturn materialReturn = load(returnId);
        requireWorksiteSide(materialReturn);
        requireBalance(materialReturn);
        materialReturn.request(TenantContext.require().userId(), clock.instant());
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_REQUESTED);
    }

    /** El almacén de origen aprueba la devolución solicitada. */
    @Transactional
    public MaterialReturn approve(UUID returnId) {
        MaterialReturn materialReturn = load(returnId);
        requireWarehouseSide(materialReturn);
        materialReturn.approve(TenantContext.require().userId(), clock.instant());
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_APPROVED);
    }

    /** AC03: el almacén de origen rechaza la devolución con su motivo; el saldo sigue en la obra. */
    @Transactional
    public MaterialReturn reject(UUID returnId, String reason) {
        MaterialReturn materialReturn = load(returnId);
        requireWarehouseSide(materialReturn);
        materialReturn.reject(reason, TenantContext.require().userId(), clock.instant());
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_REJECTED);
    }

    /** AC02: la obra inicia el retorno de la devolución aprobada; el material sale de la obra (RETURN_OUT). */
    @Transactional
    public MaterialReturn dispatch(UUID returnId) {
        MaterialReturn materialReturn = load(returnId);
        requireWorksiteSide(materialReturn);
        materialReturn.dispatch(TenantContext.require().userId(), clock.instant());
        String reference = "Devolución " + StockCountCommandService.shortId(materialReturn.id()) + " al almacén";
        for (MaterialReturnLine line : materialReturn.lines()) {
            operations.deduct(materialReturn.worksiteId(), line.materialId(), line.quantity(),
                    StockMovement.Type.RETURN_OUT, reference);
        }
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_DISPATCHED);
    }

    /**
     * AC02 y AC03: el almacén verifica lo recibido. Reincorpora únicamente lo aceptado (RETURN_IN) y registra lo
     * rechazado de cada material con su motivo.
     */
    @Transactional
    public MaterialReturn receive(UUID returnId, Map<UUID, Verification> verifications) {
        MaterialReturn materialReturn = load(returnId);
        requireWarehouseSide(materialReturn);
        List<MaterialReturnLine> verified = materialReturn.receive(verifications, TenantContext.require().userId(),
                clock.instant());
        String reference = "Devolución " + StockCountCommandService.shortId(materialReturn.id()) + " de la obra";
        for (MaterialReturnLine line : verified) {
            if (line.acceptedQty().signum() > 0) {
                operations.add(materialReturn.warehouseId(), line.materialId(), line.acceptedQty(),
                        StockMovement.Type.RETURN_IN, reference);
            }
        }
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_RECEIVED);
    }

    /** La obra anula una devolución registrada o solicitada (todavía sin aprobar). */
    @Transactional
    public MaterialReturn cancel(UUID returnId) {
        MaterialReturn materialReturn = load(returnId);
        requireWorksiteSide(materialReturn);
        materialReturn.cancel(TenantContext.require().userId(), clock.instant());
        return saveAndPublish(materialReturn, InventoryEvents.MATERIAL_RETURN_CANCELLED);
    }

    private MaterialReturn saveAndPublish(MaterialReturn materialReturn, String eventType) {
        if (!returns.update(materialReturn)) {
            throw new ConflictException("RETURN_MODIFIED",
                    "Otra persona cambió la devolución mientras la revisabas; vuelve a cargarla");
        }
        outbox.append(eventType, InventoryEvents.MATERIAL_RETURN, materialReturn.id(), event(materialReturn));
        return materialReturn;
    }

    static InventoryEvents.MaterialReturnChanged event(MaterialReturn materialReturn) {
        return new InventoryEvents.MaterialReturnChanged(materialReturn.id(), materialReturn.worksiteId(),
                materialReturn.warehouseId(), materialReturn.status().name(), materialReturn.rejectionReason(),
                materialReturn.hasRejections(), materialReturn.lines().stream()
                .map(line -> new ReturnedLine(line.materialId(), line.quantity(), line.acceptedQty(),
                        line.rejectedQty(), line.rejectionReason())).toList());
    }

    /**
     * Lo declarado de cada material no puede superar lo disponible en la obra menos lo ya comprometido en otras
     * devoluciones pendientes de la misma obra.
     */
    private void requireBalance(MaterialReturn materialReturn) {
        Map<UUID, BigDecimal> committed = new HashMap<>();
        for (MaterialReturn other : returns.findByLocations(List.of(materialReturn.worksiteId()), List.of(), null)) {
            if (other.id().equals(materialReturn.id()) || !MaterialReturn.PENDING.contains(other.status())) {
                continue;
            }
            for (MaterialReturnLine line : other.lines()) {
                committed.merge(line.materialId(), line.quantity(), BigDecimal::add);
            }
        }
        for (MaterialReturnLine line : materialReturn.lines()) {
            BigDecimal available = stock.find(materialReturn.worksiteId(), line.materialId())
                    .map(StockItem::availableQty).orElse(BigDecimal.ZERO);
            BigDecimal returnable = available.subtract(committed.getOrDefault(line.materialId(), BigDecimal.ZERO));
            if (line.quantity().compareTo(returnable) > 0) {
                throw new ConflictException("RETURN_EXCEEDS_BALANCE",
                        "La obra no tiene ese saldo para devolver: quedan "
                                + returnable.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                        List.of(new ErrorDetail("materialId", line.materialId().toString()),
                                new ErrorDetail("returnable", returnable.max(BigDecimal.ZERO).toPlainString()),
                                new ErrorDetail("requested", line.quantity().toPlainString())));
            }
        }
    }

    private static UUID originWarehouse(UUID requested, Set<UUID> materialIds, Map<UUID, Set<UUID>> origins) {
        for (UUID materialId : materialIds) {
            Set<UUID> fromWarehouses = origins.getOrDefault(materialId, Set.of());
            if (fromWarehouses.isEmpty()) {
                throw new ConflictException("ORIGIN_WAREHOUSE_UNKNOWN",
                        "No hay registro de que algún almacén haya despachado ese material a la obra",
                        List.of(new ErrorDetail("materialId", materialId.toString())));
            }
            if (requested != null && !fromWarehouses.contains(requested)) {
                throw new ConflictException("NOT_SUPPLIED_BY_WAREHOUSE",
                        "Ese almacén no despachó el material a la obra; se devuelve al almacén de origen",
                        List.of(new ErrorDetail("materialId", materialId.toString())));
            }
        }
        if (requested != null) {
            return requested;
        }
        Set<UUID> common = null;
        for (UUID materialId : materialIds) {
            Set<UUID> fromWarehouses = origins.get(materialId);
            if (common == null) {
                common = new HashSet<>(fromWarehouses);
            } else {
                common.retainAll(fromWarehouses);
            }
        }
        if (common == null || common.isEmpty()) {
            throw new ConflictException("NO_COMMON_ORIGIN_WAREHOUSE",
                    "Los materiales vienen de almacenes distintos: registra una devolución por almacén de origen");
        }
        if (common.size() > 1) {
            throw new ValidationException("ORIGIN_WAREHOUSE_REQUIRED",
                    "Los materiales llegaron de varios almacenes: indica a cuál se devuelven",
                    List.of(new ErrorDetail("warehouseId", "obligatorio")));
        }
        return common.iterator().next();
    }

    private MaterialReturn load(UUID returnId) {
        TenantInfo requester = TenantContext.require();
        return returns.find(returnId)
                .filter(found -> worksiteSide(requester, found.worksiteId())
                        || warehouseSide(requester, found.warehouseId()))
                .orElseThrow(() -> new ResourceNotFoundException("RETURN_NOT_FOUND", "La devolución no existe"));
    }

    private void requireWorksiteSide(MaterialReturn materialReturn) {
        if (!worksiteSide(TenantContext.require(), materialReturn.worksiteId())) {
            throw new ForbiddenException("RETURN_FORBIDDEN",
                    "Solo el encargado de la obra o el administrador hacen este paso de la devolución");
        }
    }

    private void requireWarehouseSide(MaterialReturn materialReturn) {
        if (!warehouseSide(TenantContext.require(), materialReturn.warehouseId())) {
            throw new ForbiddenException("RETURN_FORBIDDEN",
                    "Solo el encargado del almacén de origen o el administrador hacen este paso de la devolución");
        }
    }

    private boolean worksiteSide(TenantInfo requester, UUID worksiteId) {
        return ADMINISTRATOR.equals(requester.role())
                || SITE_MANAGER.equals(requester.role()) && directory.isAssigned(requester.userId(), worksiteId);
    }

    private boolean warehouseSide(TenantInfo requester, UUID warehouseId) {
        return ADMINISTRATOR.equals(requester.role())
                || WAREHOUSE_MANAGER.equals(requester.role()) && directory.isAssigned(requester.userId(), warehouseId);
    }
}
