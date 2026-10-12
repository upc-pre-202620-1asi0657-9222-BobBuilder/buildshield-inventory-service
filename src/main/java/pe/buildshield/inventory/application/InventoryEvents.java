package pe.buildshield.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Eventos de integración que publica inventory en {@code buildshield.inventory} (payload del sobre). Los nombres
 * de los campos son el contrato con los demás servicios (wiki/informe-tf/notas-etapa-b1.md §3): no se renombran.
 */
public final class InventoryEvents {

    public static final String STOCK_RESERVED = "StockReserved";
    public static final String STOCK_RESERVATION_REJECTED = "StockReservationRejected";
    public static final String STOCK_DEDUCTED = "StockDeducted";
    public static final String STOCK_RECEIVED = "StockReceived";
    public static final String STOCK_RELEASED = "StockReleased";
    public static final String STOCK_ENTRY_REGISTERED = "StockEntryRegistered";
    public static final String STOCK_ADJUSTED = "StockAdjusted";

    public static final String ORDER = "ORDER";
    public static final String DISPATCH = "DISPATCH";
    public static final String RECEPTION = "RECEPTION";
    public static final String STOCK_ITEM = "STOCK_ITEM";
    public static final String STOCK_COUNT = "STOCK_COUNT";

    /** Origen de lo recibido en obra. */
    public static final String SOURCE_PARTIAL = "PARTIAL";
    public static final String SOURCE_CONFIRMATION = "CONFIRMATION";

    /** Motivo de una liberación de reservas. */
    public static final String RELEASE_ORDER_REJECTED = "ORDER_REJECTED";
    public static final String RELEASE_ORDER_CLOSED = "ORDER_CLOSED";

    private InventoryEvents() {
    }

    /** Saga: todas las líneas del pedido quedaron reservadas en el almacén de origen. */
    public record StockReserved(UUID orderId, UUID warehouseId, List<ReservedLine> reservations) {
    }

    public record ReservedLine(UUID orderLineId, UUID materialId, BigDecimal quantity) {
    }

    /** Saga: alguna línea no alcanza; no se reservó nada. {@code reason} se muestra tal cual en la web. */
    public record StockReservationRejected(UUID orderId, UUID warehouseId, String reason, List<MissingLine> missing) {
    }

    public record MissingLine(UUID materialId, BigDecimal requested, BigDecimal available) {
    }

    /** Salida del almacén al partir el despacho: consume la reserva de cada línea. */
    public record StockDeducted(UUID dispatchId, UUID orderId, UUID warehouseId, List<DeductedLine> lines) {
    }

    public record DeductedLine(UUID orderLineId, UUID materialId, BigDecimal quantity, BigDecimal availableAfter,
            BigDecimal reservedAfter) {
    }

    /** Lo recibido en obra (parcial o conformidad): suma solo la diferencia. */
    public record StockReceived(UUID receptionId, UUID dispatchId, UUID worksiteId, String source,
            List<ReceivedLine> lines) {
    }

    public record ReceivedLine(UUID materialId, BigDecimal quantity, BigDecimal availableAfter) {
    }

    /** Reservas sobrantes devueltas a lo disponible al rechazar o cerrar el pedido. */
    public record StockReleased(UUID orderId, UUID warehouseId, String reason, List<ReleasedLine> lines) {
    }

    public record ReleasedLine(UUID orderLineId, UUID materialId, BigDecimal quantity) {
    }

    /**
     * Entrada de material a un almacén (carga de existencias).
     *
     * @param entryType {@code INITIAL} (inventario inicial) o {@code REPLENISHMENT} (reposición); campo agregado en
     *                  el Sprint 4, los consumidores que no lo conocen lo ignoran
     */
    public record StockEntryRegistered(UUID warehouseId, UUID materialId, BigDecimal quantity,
            BigDecimal availableAfter, BigDecimal reservedAfter, String note, String entryType) {
    }

    /** Ajuste del inventario validado tras un conteo con diferencias revisadas (US41). */
    public record StockAdjusted(UUID countId, UUID warehouseId, String reason, String reviewNote,
            List<AdjustedLine> lines) {
    }

    /** {@code difference} = contado menos sistema; positivo suma a lo disponible y negativo lo descuenta. */
    public record AdjustedLine(UUID materialId, BigDecimal systemQty, BigDecimal countedQty, BigDecimal difference,
            BigDecimal availableAfter) {
    }
}
