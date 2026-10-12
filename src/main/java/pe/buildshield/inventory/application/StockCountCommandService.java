package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.InventoryEvents.AdjustedLine;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockCountLine;
import pe.buildshield.inventory.domain.model.StockCountRepository;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conteo de validación y ajuste de inventario (US41, T91). Lo hacen el administrador o un encargado asignado al
 * almacén: iniciar el conteo, registrar lo contado, finalizarlo (el sistema compara), revisar las diferencias y
 * validar el ajuste con su motivo. El ajuste cambia el stock y publica {@code StockAdjusted} en la misma
 * transacción (Transactional Outbox).
 */
@Service
public class StockCountCommandService {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";

    private final StockCountRepository counts;
    private final StockRepository stock;
    private final StockOperations operations;
    private final SiteDirectory directory;
    private final OutboxWriter outbox;
    private final Clock clock;

    public StockCountCommandService(StockCountRepository counts, StockRepository stock, StockOperations operations,
            SiteDirectory directory, OutboxWriter outbox, Clock clock) {
        this.counts = counts;
        this.stock = stock;
        this.operations = operations;
        this.directory = directory;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Inicia un conteo de validación del almacén.
     *
     * @param materialIds materiales a contar; si es vacío, todos los que tienen stock registrado en el almacén
     * @throws ConflictException {@code SITE_INACTIVE} o {@code COUNT_ALREADY_OPEN}
     */
    @Transactional
    public StockCount start(UUID warehouseId, List<UUID> materialIds, String note) {
        TenantInfo requester = TenantContext.require();
        Site warehouse = directory.findLocation(warehouseId)
                .filter(Site::isWarehouse)
                .filter(found -> canManage(requester, found.id()))
                .orElseThrow(() -> new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "El almacén no existe"));
        if (!warehouse.active()) {
            throw new ConflictException("SITE_INACTIVE", "El almacén está desactivado");
        }
        List<UUID> materials = materialIds == null || materialIds.isEmpty()
                ? stock.findByLocations(List.of(warehouseId)).stream().map(StockItem::materialId).toList()
                : materialIds;
        Map<UUID, MaterialInfo> known = directory.findMaterials(materials);
        for (UUID materialId : materials) {
            if (!known.containsKey(materialId)) {
                throw new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material no existe",
                        List.of(new ErrorDetail("materialId", String.valueOf(materialId))));
            }
        }
        if (counts.hasOpenCount(warehouseId)) {
            throw new ConflictException("COUNT_ALREADY_OPEN",
                    "El almacén ya tiene un conteo abierto; finalízalo, ajústalo o anúlalo antes de iniciar otro");
        }
        StockCount count = StockCount.start(warehouseId, materials, note, requester.userId(), clock.instant());
        counts.add(count);
        return count;
    }

    /** Registra (o corrige) lo contado de uno o varios materiales del conteo en curso. */
    @Transactional
    public StockCount registerCounts(UUID countId, Map<UUID, BigDecimal> counted) {
        StockCount count = load(countId);
        count.registerCounts(counted);
        save(count);
        return count;
    }

    /** AC01: finaliza el conteo y el sistema compara lo contado con las existencias (disponible más reservado). */
    @Transactional
    public StockCount finish(UUID countId) {
        StockCount count = load(countId);
        Map<UUID, BigDecimal> system = new HashMap<>();
        for (StockCountLine line : count.lines()) {
            stock.find(count.warehouseId(), line.materialId())
                    .ifPresent(item -> system.put(line.materialId(), item.availableQty().add(item.reservedQty())));
        }
        count.finish(system, TenantContext.require().userId(), clock.instant());
        save(count);
        return count;
    }

    /** Deja constancia de la revisión de las diferencias (requisito para ajustar). */
    @Transactional
    public StockCount review(UUID countId, String note) {
        StockCount count = load(countId);
        count.review(note, TenantContext.require().userId(), clock.instant());
        save(count);
        return count;
    }

    /**
     * AC02: valida el ajuste de una diferencia revisada. Aplica a lo disponible la diferencia de cada material
     * (ADJUSTMENT_IN o ADJUSTMENT_OUT) y publica {@code StockAdjusted}.
     *
     * @throws pe.buildshield.inventory.domain.model.InsufficientStockException si un faltante es mayor que lo
     *                                                                         disponible (lo demás está reservado)
     */
    @Transactional
    public StockCount adjust(UUID countId, String reason) {
        StockCount count = load(countId);
        List<StockCountLine> differences = count.adjust(reason, TenantContext.require().userId(), clock.instant());
        save(count);
        String reference = "Ajuste por conteo " + shortId(count.id()) + ": " + count.adjustmentReason();
        List<AdjustedLine> adjusted = new ArrayList<>();
        for (StockCountLine line : differences) {
            BigDecimal difference = line.difference();
            StockLevel level = difference.signum() > 0
                    ? operations.add(count.warehouseId(), line.materialId(), difference,
                    StockMovement.Type.ADJUSTMENT_IN, reference)
                    : operations.deduct(count.warehouseId(), line.materialId(), difference.negate(),
                    StockMovement.Type.ADJUSTMENT_OUT, reference);
            adjusted.add(new AdjustedLine(line.materialId(), line.systemQty(), line.countedQty(), difference,
                    level.availableQty()));
        }
        outbox.append(InventoryEvents.STOCK_ADJUSTED, InventoryEvents.STOCK_COUNT, count.id(),
                new InventoryEvents.StockAdjusted(count.id(), count.warehouseId(), count.adjustmentReason(),
                        count.reviewNote(), adjusted));
        return count;
    }

    /** Anula un conteo abierto sin tocar el inventario. */
    @Transactional
    public StockCount cancel(UUID countId) {
        StockCount count = load(countId);
        count.cancel(TenantContext.require().userId(), clock.instant());
        save(count);
        return count;
    }

    private StockCount load(UUID countId) {
        TenantInfo requester = TenantContext.require();
        return counts.find(countId)
                .filter(count -> canManage(requester, count.warehouseId()))
                .orElseThrow(() -> new ResourceNotFoundException("COUNT_NOT_FOUND", "El conteo no existe"));
    }

    private void save(StockCount count) {
        if (!counts.update(count)) {
            throw new ConflictException("COUNT_MODIFIED",
                    "Otra persona cambió el conteo mientras lo editabas; vuelve a cargarlo");
        }
    }

    private boolean canManage(TenantInfo requester, UUID warehouseId) {
        return ADMINISTRATOR.equals(requester.role())
                || WAREHOUSE_MANAGER.equals(requester.role()) && directory.isAssigned(requester.userId(), warehouseId);
    }

    static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
