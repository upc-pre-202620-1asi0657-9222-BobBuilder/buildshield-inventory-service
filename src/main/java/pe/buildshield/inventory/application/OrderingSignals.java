package pe.buildshield.inventory.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Lo que inventory necesita de cada evento de {@code buildshield.ordering}, ya leído del sobre por el consumidor
 * (infraestructura). Los casos de uso de la saga los reciben así, sin depender del JSON.
 */
public final class OrderingSignals {

    private OrderingSignals() {
    }

    /** {@code OrderApproved}: reservar en el almacén de origen lo solicitado de cada línea. */
    public record OrderApproved(UUID orderId, UUID warehouseId, List<OrderLine> lines) {
    }

    public record OrderLine(UUID orderLineId, UUID materialId, BigDecimal quantity) {
    }

    /** {@code DispatchDeparted}: consumir la reserva de cada línea despachada y descontar del almacén. */
    public record DispatchDeparted(UUID dispatchId, UUID orderId, UUID warehouseId, String manifestCode,
            List<OrderLine> lines) {
    }

    /**
     * {@code ReceptionPartiallyReceived} o {@code ReceptionConfirmed}: sumar a la obra solo el
     * {@code deltaQuantity} de cada línea.
     *
     * @param source {@link InventoryEvents#SOURCE_PARTIAL} o {@link InventoryEvents#SOURCE_CONFIRMATION}
     */
    public record ReceptionReceived(UUID receptionId, UUID dispatchId, UUID worksiteId, String source,
            List<ReceivedLine> lines) {
    }

    public record ReceivedLine(UUID materialId, BigDecimal deltaQuantity) {
    }

    /**
     * {@code OrderRejected} u {@code OrderClosed}: liberar lo reservado que no se consumió.
     *
     * @param reason {@link InventoryEvents#RELEASE_ORDER_REJECTED} o {@link InventoryEvents#RELEASE_ORDER_CLOSED}
     */
    public record OrderEnded(UUID orderId, String reason) {
    }
}
