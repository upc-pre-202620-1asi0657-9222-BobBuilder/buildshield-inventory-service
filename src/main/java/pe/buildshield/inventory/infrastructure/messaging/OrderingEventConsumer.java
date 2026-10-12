package pe.buildshield.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.inventory.application.InventoryEvents;
import pe.buildshield.inventory.application.OrderingSignals;
import pe.buildshield.inventory.application.StockCommandService;
import pe.buildshield.inventory.shared.messaging.EventEnvelope;
import pe.buildshield.inventory.shared.messaging.IdempotentEventProcessor;

import java.util.List;

import static pe.buildshield.inventory.infrastructure.messaging.Payloads.decimal;
import static pe.buildshield.inventory.infrastructure.messaging.Payloads.list;
import static pe.buildshield.inventory.infrastructure.messaging.Payloads.optionalUuid;
import static pe.buildshield.inventory.infrastructure.messaging.Payloads.text;
import static pe.buildshield.inventory.infrastructure.messaging.Payloads.uuid;

/**
 * Consume {@code buildshield.ordering}: inventory es participante de la saga de reserva (plan §6).
 *
 * <ul>
 *   <li>{@code OrderApproved}: reserva todo o nada y responde {@code StockReserved} o
 *       {@code StockReservationRejected}.</li>
 *   <li>{@code DispatchDeparted}: consume la reserva y descuenta del almacén ({@code StockDeducted}).</li>
 *   <li>{@code ReceptionPartiallyReceived} y {@code ReceptionConfirmed}: suma a la obra solo el
 *       {@code deltaQuantity} ({@code StockReceived}).</li>
 *   <li>{@code OrderRejected} y {@code OrderClosed}: libera lo reservado sin consumir ({@code StockReleased}).</li>
 * </ul>
 *
 * Cada evento se procesa una sola vez ({@link IdempotentEventProcessor}) y en una transacción con su efecto y
 * el evento de respuesta (outbox). Los demás tipos se ignoran.
 */
@Component
public class OrderingEventConsumer {

    static final String ORDER_APPROVED = "OrderApproved";
    static final String ORDER_REJECTED = "OrderRejected";
    static final String ORDER_CLOSED = "OrderClosed";
    static final String DISPATCH_DEPARTED = "DispatchDeparted";
    static final String RECEPTION_PARTIALLY_RECEIVED = "ReceptionPartiallyReceived";
    static final String RECEPTION_CONFIRMED = "ReceptionConfirmed";

    private static final Logger log = LoggerFactory.getLogger(OrderingEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final StockCommandService stock;

    public OrderingEventConsumer(IdempotentEventProcessor processor, StockCommandService stock) {
        this.processor = processor;
        this.stock = stock;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.ordering:buildshield.ordering}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, this::handle);
    }

    void handle(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case ORDER_APPROVED -> stock.reserveForOrder(new OrderingSignals.OrderApproved(uuid(payload, "orderId"),
                    uuid(payload, "warehouseId"), orderLines(payload)));
            case DISPATCH_DEPARTED -> stock.deductForDispatch(new OrderingSignals.DispatchDeparted(
                    uuid(payload, "dispatchId"), uuid(payload, "orderId"), uuid(payload, "warehouseId"),
                    text(payload, "manifestCode"), orderLines(payload)));
            case RECEPTION_PARTIALLY_RECEIVED -> stock.receiveAtWorksite(reception(payload,
                    InventoryEvents.SOURCE_PARTIAL));
            case RECEPTION_CONFIRMED -> stock.receiveAtWorksite(reception(payload,
                    InventoryEvents.SOURCE_CONFIRMATION));
            case ORDER_REJECTED -> stock.releaseForOrder(new OrderingSignals.OrderEnded(uuid(payload, "orderId"),
                    InventoryEvents.RELEASE_ORDER_REJECTED));
            case ORDER_CLOSED -> stock.releaseForOrder(new OrderingSignals.OrderEnded(uuid(payload, "orderId"),
                    InventoryEvents.RELEASE_ORDER_CLOSED));
            default -> log.debug("Evento {} ignorado por inventory", event.eventType());
        }
    }

    private static List<OrderingSignals.OrderLine> orderLines(JsonNode payload) {
        return list(payload, "lines", line -> new OrderingSignals.OrderLine(uuid(line, "orderLineId"),
                uuid(line, "materialId"), decimal(line, "quantity")));
    }

    private static OrderingSignals.ReceptionReceived reception(JsonNode payload, String source) {
        return new OrderingSignals.ReceptionReceived(uuid(payload, "receptionId"), uuid(payload, "dispatchId"),
                uuid(payload, "worksiteId"), optionalUuid(payload, "warehouseId"), source, list(payload, "lines",
                line -> new OrderingSignals.ReceivedLine(uuid(line, "materialId"), decimal(line, "deltaQuantity"))));
    }
}
