package pe.buildshield.inventory.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.InventoryEvents.AdjustedLine;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.StockAdjustment;
import pe.buildshield.inventory.domain.model.StockAdjustmentRepository;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.TimeOrderedId;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.error.ValidationException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Conteo físico y ajuste de inventario (US41, T91), en {@code stock_adjustments}. Lo hacen el administrador o un
 * encargado asignado al almacén, en dos pasos sin estado guardado: {@link #compare} muestra las diferencias sin guardar
 * nada y {@link #apply} las aplica con su motivo en una sola transacción, con su evento {@code StockAdjusted} por
 * material (Transactional Outbox). Lo contado se compara con disponible más reservado.
 */
@Service
public class StockAdjustmentCommandService {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    static final String WAREHOUSE_MANAGER = "WAREHOUSE_MANAGER";
    static final String MATCHED = "MATCHED";
    static final String DIFFERENCES_FOUND = "DIFFERENCES_FOUND";
    static final String ADJUSTED = "ADJUSTED";
    static final String NO_DIFFERENCE_REASON = "Conteo sin diferencias";

    private final StockAdjustmentRepository adjustments;
    private final StockRepository stock;
    private final StockOperations operations;
    private final SiteDirectory directory;
    private final OutboxWriter outbox;
    private final Clock clock;

    public StockAdjustmentCommandService(StockAdjustmentRepository adjustments, StockRepository stock,
            StockOperations operations, SiteDirectory directory, OutboxWriter outbox, Clock clock) {
        this.adjustments = adjustments;
        this.stock = stock;
        this.operations = operations;
        this.directory = directory;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Lo contado de un material. */
    public record CountedLine(UUID materialId, BigDecimal countedQty) {
    }

    /** Resultado de un conteo. {@code adjustmentId} es nulo si no se guardó nada. */
    public record CountedResult(UUID warehouseId, String status, String reason, List<ResultLine> lines) {
    }

    public record ResultLine(UUID adjustmentId, UUID materialId, BigDecimal countedQty, BigDecimal systemQty) {

        public BigDecimal difference() {
            return countedQty.subtract(systemQty);
        }
    }

    /**
     * AC01: compara lo contado con las existencias, sin guardar nada ni tocar el inventario.
     *
     * @return estado {@code MATCHED} o {@code DIFFERENCES_FOUND}
     */
    @Transactional(readOnly = true)
    public CountedResult compare(UUID warehouseId, List<CountedLine> counted) {
        validate(warehouseId, counted);
        List<ResultLine> lines = new ArrayList<>();
        for (CountedLine line : counted) {
            lines.add(new ResultLine(null, line.materialId(), StockAdjustment.countedQuantity(line.countedQty()),
                    systemQuantity(warehouseId, line.materialId())));
        }
        boolean differences = lines.stream().anyMatch(line -> line.difference().signum() != 0);
        return new CountedResult(warehouseId, differences ? DIFFERENCES_FOUND : MATCHED, null, lines);
    }

    /**
     * AC02: registra el conteo y aplica a lo disponible la diferencia de cada material (ADJUSTMENT_IN o ADJUSTMENT_OUT).
     * Sin diferencias el motivo es opcional y solo queda la constancia.
     *
     * @return estado {@code MATCHED} o {@code ADJUSTED}
     * @throws ValidationException {@code ADJUSTMENT_REASON_REQUIRED} si hay diferencias y falta el motivo
     * @throws pe.buildshield.inventory.domain.model.InsufficientStockException si un faltante es mayor que lo
     *                                                                         disponible (lo demás está reservado)
     */
    @Transactional
    public CountedResult apply(UUID warehouseId, List<CountedLine> counted, String reason) {
        validate(warehouseId, counted);
        UUID by = TenantContext.require().userId();
        Instant now = clock.instant();
        String cleaned = cleanReason(reason);
        boolean differences = false;
        List<ResultLine> lines = new ArrayList<>();
        for (CountedLine line : counted) {
            BigDecimal countedQty = StockAdjustment.countedQuantity(line.countedQty());
            BigDecimal systemQty = systemQuantity(warehouseId, line.materialId());
            BigDecimal difference = countedQty.subtract(systemQty);
            if (difference.signum() != 0 && cleaned == null) {
                throw new ValidationException("ADJUSTMENT_REASON_REQUIRED", "El ajuste necesita un motivo",
                        List.of(new ErrorDetail("reason", "obligatorio")));
            }
            differences |= difference.signum() != 0;
            UUID adjustmentId = TimeOrderedId.next();
            String applied = cleaned == null ? NO_DIFFERENCE_REASON : cleaned;
            StockLevel level = applyDifference(warehouseId, line.materialId(), difference, adjustmentId);
            UUID stockItemId = level != null ? level.stockItemId()
                    : stock.find(warehouseId, line.materialId()).map(StockItem::id).orElse(null);
            if (stockItemId == null) {
                // Contó cero de un material sin existencia: no hay existencia que ajustar ni fila que guardar.
                lines.add(new ResultLine(null, line.materialId(), countedQty, systemQty));
                continue;
            }
            adjustments.add(new StockAdjustment(adjustmentId, stockItemId, warehouseId, line.materialId(), countedQty,
                    systemQty, applied, by, now));
            lines.add(new ResultLine(adjustmentId, line.materialId(), countedQty, systemQty));
            if (level != null) {
                outbox.append(InventoryEvents.STOCK_ADJUSTED, InventoryEvents.STOCK_ITEM, stockItemId,
                        new InventoryEvents.StockAdjusted(adjustmentId, warehouseId, applied, null,
                                List.of(new AdjustedLine(line.materialId(), systemQty, countedQty, difference,
                                        level.availableQty()))));
            }
        }
        return new CountedResult(warehouseId, differences ? ADJUSTED : MATCHED,
                cleaned == null ? NO_DIFFERENCE_REASON : cleaned, lines);
    }

    private StockLevel applyDifference(UUID warehouseId, UUID materialId, BigDecimal difference, UUID adjustmentId) {
        if (difference.signum() > 0) {
            return operations.add(warehouseId, materialId, difference, StockMovement.Type.ADJUSTMENT_IN, adjustmentId);
        }
        if (difference.signum() < 0) {
            return operations.deduct(warehouseId, materialId, difference.negate(), StockMovement.Type.ADJUSTMENT_OUT,
                    adjustmentId);
        }
        return null;
    }

    private BigDecimal systemQuantity(UUID warehouseId, UUID materialId) {
        return stock.find(warehouseId, materialId).map(item -> item.availableQty().add(item.reservedQty()))
                .orElse(BigDecimal.ZERO).setScale(3, RoundingMode.UNNECESSARY);
    }

    private void validate(UUID warehouseId, List<CountedLine> counted) {
        TenantInfo requester = TenantContext.require();
        Site warehouse = directory.findLocation(warehouseId)
                .filter(Site::isWarehouse)
                .filter(found -> canManage(requester, found.id()))
                .orElseThrow(() -> new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "El almacén no existe"));
        if (!warehouse.active()) {
            throw new ConflictException("SITE_INACTIVE", "El almacén está desactivado");
        }
        if (counted == null || counted.isEmpty()) {
            throw new ValidationException("COUNT_WITHOUT_MATERIALS", "El conteo necesita al menos un material");
        }
        Set<UUID> seen = new HashSet<>();
        for (CountedLine line : counted) {
            if (!seen.add(line.materialId())) {
                throw new ValidationException("DUPLICATED_MATERIAL", "Cada material va una sola vez en el conteo",
                        List.of(new ErrorDetail("materialId", String.valueOf(line.materialId()))));
            }
        }
        Map<UUID, MaterialInfo> known = directory.findMaterials(seen);
        for (UUID materialId : seen) {
            if (!known.containsKey(materialId)) {
                throw new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material no existe",
                        List.of(new ErrorDetail("materialId", String.valueOf(materialId))));
            }
        }
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > StockAdjustment.REASON_MAX) {
            throw new ValidationException("TEXT_TOO_LONG", "El motivo supera los " + StockAdjustment.REASON_MAX
                    + " caracteres", List.of(new ErrorDetail("reason", "máximo " + StockAdjustment.REASON_MAX
                    + " caracteres")));
        }
        return trimmed;
    }

    private boolean canManage(TenantInfo requester, UUID warehouseId) {
        return ADMINISTRATOR.equals(requester.role())
                || WAREHOUSE_MANAGER.equals(requester.role()) && directory.isAssigned(requester.userId(), warehouseId);
    }
}
