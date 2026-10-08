package pe.buildshield.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.application.InventoryEvents.DeductedLine;
import pe.buildshield.inventory.application.InventoryEvents.MissingLine;
import pe.buildshield.inventory.application.InventoryEvents.ReceivedLine;
import pe.buildshield.inventory.application.InventoryEvents.ReleasedLine;
import pe.buildshield.inventory.application.InventoryEvents.ReservedLine;
import pe.buildshield.inventory.application.OrderingSignals.DispatchDeparted;
import pe.buildshield.inventory.application.OrderingSignals.OrderApproved;
import pe.buildshield.inventory.application.OrderingSignals.OrderEnded;
import pe.buildshield.inventory.application.OrderingSignals.OrderLine;
import pe.buildshield.inventory.application.OrderingSignals.ReceptionReceived;
import pe.buildshield.inventory.application.SiteDirectory.MaterialInfo;
import pe.buildshield.inventory.application.SiteDirectory.Site;
import pe.buildshield.inventory.domain.model.Quantity;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ResourceNotFoundException;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso que cambian el stock. Cada cambio y su evento de integración se escriben en la misma
 * transacción (Transactional Outbox).
 *
 * <ul>
 *   <li>Carga de existencias por REST (administrador o encargado del almacén).</li>
 *   <li>Saga de reserva (plan §6): {@code OrderApproved} reserva todo o nada y responde {@code StockReserved} o
 *       {@code StockReservationRejected}; {@code DispatchDeparted} consume la reserva y descuenta del almacén;
 *       lo recibido en obra suma solo el {@code deltaQuantity}; {@code OrderRejected} y {@code OrderClosed}
 *       liberan lo reservado que no se consumió.</li>
 * </ul>
 *
 * <p>Los casos de la saga corren dentro de la transacción del consumidor idempotente: si fallan, nada queda
 * escrito (ni el evento como procesado) y Kafka reintenta.
 */
@Service
public class StockCommandService {

    static final String ADMINISTRATOR = "ADMINISTRATOR";
    private static final Logger log = LoggerFactory.getLogger(StockCommandService.class);

    private final StockOperations operations;
    private final StockRepository stock;
    private final SiteDirectory directory;
    private final OutboxWriter outbox;

    public StockCommandService(StockOperations operations, StockRepository stock, SiteDirectory directory,
            OutboxWriter outbox) {
        this.operations = operations;
        this.stock = stock;
        this.directory = directory;
        this.outbox = outbox;
    }

    /** Entrada de material a un almacén activo: el administrador o un encargado asignado a ese almacén. */
    @Transactional
    public StockLevel registerEntry(UUID warehouseId, UUID materialId, BigDecimal quantity, String note) {
        TenantInfo requester = TenantContext.require();
        Site warehouse = directory.findLocation(warehouseId)
                .filter(Site::isWarehouse)
                .filter(found -> ADMINISTRATOR.equals(requester.role())
                        || directory.isAssigned(requester.userId(), found.id()))
                .orElseThrow(() -> new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "El almacén no existe"));
        if (!warehouse.active()) {
            throw new ConflictException("SITE_INACTIVE", "El almacén está desactivado");
        }
        MaterialInfo material = directory.findMaterial(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("MATERIAL_NOT_FOUND", "El material no existe"));
        if (!material.active()) {
            throw new ConflictException("MATERIAL_INACTIVE", "El material está retirado del catálogo");
        }
        String reference = note == null || note.isBlank() ? null : note.trim();
        StockLevel level = operations.add(warehouseId, materialId, quantity, reference);
        outbox.append(InventoryEvents.STOCK_ENTRY_REGISTERED, InventoryEvents.STOCK_ITEM, level.stockItemId(),
                new InventoryEvents.StockEntryRegistered(warehouseId, materialId, new Quantity(quantity).value(),
                        level.availableQty(), level.reservedQty(), reference));
        return level;
    }

    /**
     * Saga, paso 2: reserva todo o nada en el almacén de origen. Si alguna línea no alcanza no reserva nada y
     * publica {@code StockReservationRejected} con lo que falta. Si el pedido ya estaba reservado (evento
     * repetido con otro id) vuelve a publicar {@code StockReserved} sin reservar otra vez.
     *
     * @return {@code true} si quedó reservado
     */
    @Transactional
    public boolean reserveForOrder(OrderApproved order) {
        List<StockReservation> existing = stock.findReservationsByOrder(order.orderId());
        if (!existing.isEmpty()) {
            log.info("El pedido {} ya tenía sus reservas: se confirma de nuevo", order.orderId());
            outbox.append(InventoryEvents.STOCK_RESERVED, InventoryEvents.ORDER, order.orderId(),
                    new InventoryEvents.StockReserved(order.orderId(), order.warehouseId(), existing.stream()
                            .map(reservation -> new ReservedLine(reservation.orderLineId(), reservation.materialId(),
                                    reservation.quantity())).toList()));
            return true;
        }
        List<MissingLine> missing = new ArrayList<>();
        for (OrderLine line : order.lines()) {
            BigDecimal requested = new Quantity(line.quantity()).value();
            BigDecimal available = stock.find(order.warehouseId(), line.materialId())
                    .map(StockItem::availableQty).orElse(BigDecimal.ZERO.setScale(Quantity.SCALE));
            if (available.compareTo(requested) < 0) {
                missing.add(new MissingLine(line.materialId(), requested, available));
            }
        }
        if (!missing.isEmpty()) {
            String reason = rejectionReason(missing);
            outbox.append(InventoryEvents.STOCK_RESERVATION_REJECTED, InventoryEvents.ORDER, order.orderId(),
                    new InventoryEvents.StockReservationRejected(order.orderId(), order.warehouseId(), reason, missing));
            log.info("Reserva del pedido {} rechazada: {}", order.orderId(), reason);
            return false;
        }
        List<ReservedLine> reserved = new ArrayList<>();
        for (OrderLine line : order.lines()) {
            // Si otra operación se llevó el stock entre la revisión y la reserva, falla toda la transacción y
            // el reintento del evento lo revisa de nuevo (y entonces lo rechaza).
            operations.reserve(order.warehouseId(), line.materialId(), line.quantity(), order.orderId(),
                    line.orderLineId());
            reserved.add(new ReservedLine(line.orderLineId(), line.materialId(), new Quantity(line.quantity()).value()));
        }
        outbox.append(InventoryEvents.STOCK_RESERVED, InventoryEvents.ORDER, order.orderId(),
                new InventoryEvents.StockReserved(order.orderId(), order.warehouseId(), reserved));
        return true;
    }

    /**
     * Saga, paso 4: el despacho partió. Consume la reserva de cada línea (reservado a salida). Si una línea ya no
     * tiene reserva (por ejemplo, se liberó) descuenta directo de lo disponible; si tampoco alcanza, falla.
     */
    @Transactional
    public void deductForDispatch(DispatchDeparted dispatch) {
        String reference = "Despacho " + (dispatch.manifestCode() == null ? dispatch.dispatchId() : dispatch.manifestCode());
        List<DeductedLine> lines = new ArrayList<>();
        for (OrderLine line : dispatch.lines()) {
            StockLevel level;
            try {
                level = operations.consumeReservation(dispatch.warehouseId(), line.materialId(), line.quantity(),
                        line.orderLineId(), reference);
            } catch (ConflictException ex) {
                if (!StockOperations.RESERVATION_NOT_AVAILABLE.equals(ex.getCode())) {
                    throw ex;
                }
                log.warn("La línea {} del despacho {} no tenía reserva suficiente: se descuenta de lo disponible",
                        line.orderLineId(), dispatch.dispatchId());
                level = operations.deduct(dispatch.warehouseId(), line.materialId(), line.quantity(), reference);
            }
            lines.add(new DeductedLine(line.orderLineId(), line.materialId(), new Quantity(line.quantity()).value(),
                    level.availableQty(), level.reservedQty()));
        }
        outbox.append(InventoryEvents.STOCK_DEDUCTED, InventoryEvents.DISPATCH, dispatch.dispatchId(),
                new InventoryEvents.StockDeducted(dispatch.dispatchId(), dispatch.orderId(), dispatch.warehouseId(), lines));
    }

    /** Saga, paso 5: suma a la obra solo la diferencia recibida de cada línea (parcial o conformidad). */
    @Transactional
    public void receiveAtWorksite(ReceptionReceived reception) {
        List<ReceivedLine> lines = new ArrayList<>();
        for (OrderingSignals.ReceivedLine line : reception.lines()) {
            if (line.deltaQuantity() == null || line.deltaQuantity().signum() <= 0) {
                continue;
            }
            StockLevel level = operations.add(reception.worksiteId(), line.materialId(), line.deltaQuantity(),
                    "Recepción " + reception.receptionId());
            lines.add(new ReceivedLine(line.materialId(), new Quantity(line.deltaQuantity()).value(),
                    level.availableQty()));
        }
        if (lines.isEmpty()) {
            log.debug("La recepción {} no trae nada nuevo para la obra", reception.receptionId());
            return;
        }
        outbox.append(InventoryEvents.STOCK_RECEIVED, InventoryEvents.RECEPTION, reception.receptionId(),
                new InventoryEvents.StockReceived(reception.receptionId(), reception.dispatchId(),
                        reception.worksiteId(), reception.source(), lines));
    }

    /** Saga, paso 6: el pedido se rechazó o se cerró; lo reservado que no se consumió vuelve a lo disponible. */
    @Transactional
    public void releaseForOrder(OrderEnded order) {
        List<ReleasedLine> released = new ArrayList<>();
        UUID warehouseId = null;
        for (StockReservation reservation : stock.findReservationsByOrder(order.orderId())) {
            Optional<StockLevel> level = operations.release(reservation, "Liberación del pedido " + order.orderId());
            if (level.isPresent()) {
                warehouseId = reservation.locationId();
                released.add(new ReleasedLine(reservation.orderLineId(), reservation.materialId(),
                        reservation.remaining()));
            }
        }
        if (released.isEmpty()) {
            return;
        }
        outbox.append(InventoryEvents.STOCK_RELEASED, InventoryEvents.ORDER, order.orderId(),
                new InventoryEvents.StockReleased(order.orderId(), warehouseId, order.reason(), released));
    }

    private String rejectionReason(List<MissingLine> missing) {
        Map<UUID, MaterialInfo> materials = directory.findMaterials(missing.stream().map(MissingLine::materialId).toList());
        List<String> parts = missing.stream().map(line -> {
            MaterialInfo material = materials.get(line.materialId());
            String name = material == null ? line.materialId().toString() : material.sku();
            return name + " (hay " + plain(line.available()) + ", se pidió " + plain(line.requested()) + ")";
        }).toList();
        return "Stock insuficiente en el almacén para " + String.join(", ", parts);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
