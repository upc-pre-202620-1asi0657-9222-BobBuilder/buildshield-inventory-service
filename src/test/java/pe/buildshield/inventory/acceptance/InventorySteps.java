package pe.buildshield.inventory.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.inventory.shared.messaging.Topics;
import pe.buildshield.inventory.support.TestEvents;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.inventory.acceptance.ApiRequest.get;
import static pe.buildshield.inventory.acceptance.ApiRequest.post;
import static pe.buildshield.inventory.acceptance.IdentitySteps.EVENT_TIMEOUT;

/**
 * Pasos de carga y consulta de existencias (portados del Core) y de la saga de reserva: los eventos de ordering
 * se publican en {@code buildshield.ordering} como lo haría ordering-service, y las respuestas de inventory se
 * leen del outbox ({@code outbox_events}) que su publicador envía a {@code buildshield.inventory}.
 */
public class InventorySteps {

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    // ---------- Carga y consulta de existencias ----------

    @Cuando("registro una entrada de {string} del material {string} en el almacén {string}")
    public void registerEntry(String quantity, String sku, String warehouse) throws Exception {
        session.send(post("/api/v1/stock/entries"), Map.of(
                "warehouseId", session.id(warehouse).toString(),
                "materialId", session.id(sku.toUpperCase()).toString(),
                "quantity", new BigDecimal(quantity)));
    }

    @Dado("que el almacén {string} tiene {string} del material {string}")
    public void warehouseHasStock(String warehouse, String quantity, String sku) throws Exception {
        registerEntry(quantity, sku, warehouse);
        session.expectStatus(201);
    }

    @Entonces("el stock disponible del material {string} en el almacén {string} es {string}")
    public void availableStockIs(String sku, String warehouse, String expected) {
        await().pollInSameThread().atMost(EVENT_TIMEOUT).untilAsserted(() ->
                assertThat(stockItem(sku, warehouse).path("availableQty").decimalValue()).isEqualByComparingTo(expected));
    }

    @Entonces("el stock reservado del material {string} en el almacén {string} es {string}")
    public void reservedStockIs(String sku, String warehouse, String expected) {
        await().pollInSameThread().atMost(EVENT_TIMEOUT).untilAsserted(() ->
                assertThat(stockItem(sku, warehouse).path("reservedQty").decimalValue()).isEqualByComparingTo(expected));
    }

    @Cuando("consulto el stock")
    public void listStock() throws Exception {
        session.send(get("/api/v1/stock"), null);
    }

    @Entonces("el stock listado es solo del almacén {string}")
    public void stockOnlyFrom(String warehouse) throws Exception {
        session.expectStatus(200);
        JsonNode items = session.body();
        assertThat(items.size()).as("hay stock listado").isPositive();
        for (JsonNode item : items) {
            assertThat(item.path("warehouseId").asText()).isEqualTo(session.id(warehouse).toString());
        }
    }

    @Entonces("el stock de la obra {string} del material {string} es {string}")
    public void worksiteStockIs(String worksite, String sku, String expected) {
        await().pollInSameThread().atMost(EVENT_TIMEOUT).untilAsserted(() -> {
            ApiResponse response = session.perform(get("/api/v1/stock/worksites")
                    .param("worksiteId", session.id(worksite).toString()), null, session.accessToken());
            assertThat(response.status()).isEqualTo(200);
            JsonNode found = null;
            for (JsonNode item : session.read(response)) {
                if (item.path("sku").asText().equalsIgnoreCase(sku)) {
                    found = item;
                }
            }
            assertThat(found).as("stock de %s en la obra %s", sku, worksite).isNotNull();
            assertThat(found.path("availableQty").decimalValue()).isEqualByComparingTo(expected);
        });
    }

    @Entonces("la obra {string} no tiene stock")
    public void worksiteHasNoStock(String worksite) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock/worksites")
                .param("worksiteId", session.id(worksite).toString()), null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        assertThat(session.read(response).size()).isZero();
    }

    // ---------- Saga de reserva (eventos de ordering) ----------

    @Cuando("se aprueba el pedido {string} de la obra {string} al almacén {string} con {string} del material {string}")
    public void orderApproved(String order, String worksite, String warehouse, String quantity, String sku)
            throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        session.put(order, orderId);
        session.put(order + "/" + sku.toUpperCase(), lineId);
        session.put(order + "/almacén", session.id(warehouse));
        session.put(order + "/obra", session.id(worksite));
        String envelope = TestEvents.envelope(json, "OrderApproved", organization(), "ORDER", orderId,
                TestEvents.orderApproved(orderId, session.id(warehouse), session.id(worksite), List.of(
                        TestEvents.orderLine(lineId, session.id(sku.toUpperCase()), quantity))));
        session.putText(order + "/aprobación", envelope);
        publish(orderId, envelope);
    }

    @Cuando("ordering vuelve a enviar la aprobación del pedido {string}")
    public void approvalRedelivered(String order) throws Exception {
        UUID orderId = session.id(order);
        publish(orderId, session.text(order + "/aprobación"));
        // Marca con la misma clave (misma partición): cuando se procesa, el duplicado ya pasó por el consumidor.
        UUID marker = UUID.randomUUID();
        publish(orderId, TestEvents.envelope(json, marker, "OrderRegistered", organization(), "ORDER", orderId,
                Map.of("orderId", orderId)));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, marker) == 1);
    }

    @Entonces("inventario confirma la reserva del pedido {string}")
    public void reservationConfirmed(String order) {
        JsonNode payload = awaitOutbox("StockReserved", session.id(order));
        assertThat(payload.path("orderId").asText()).isEqualTo(session.id(order).toString());
        assertThat(payload.path("reservations").size()).isPositive();
    }

    @Entonces("inventario rechaza la reserva del pedido {string} con el motivo {string}")
    public void reservationRejected(String order, String reason) {
        JsonNode payload = awaitOutbox("StockReservationRejected", session.id(order));
        assertThat(payload.path("reason").asText()).isEqualTo(reason);
        assertThat(payload.path("missing").size()).isPositive();
    }

    @Entonces("el pedido {string} tiene una sola reserva confirmada una sola vez")
    public void reservedOnce(String order) {
        assertThat(outboxCount("StockReserved", session.id(order))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE order_id = ?", Long.class,
                session.id(order))).isEqualTo(1);
    }

    @Cuando("parte el despacho {string} del pedido {string} con {string} del material {string}")
    public void dispatchDeparted(String dispatch, String order, String quantity, String sku) throws Exception {
        UUID dispatchId = UUID.randomUUID();
        session.put(dispatch, dispatchId);
        session.put(dispatch + "/obra", session.id(order + "/obra"));
        publish(dispatchId, TestEvents.envelope(json, "DispatchDeparted", organization(), "DISPATCH", dispatchId,
                TestEvents.dispatchDeparted(dispatchId, session.id(order), session.id(order + "/almacén"),
                        session.id(order + "/obra"), List.of(TestEvents.orderLine(session.id(order + "/" + sku.toUpperCase()),
                                session.id(sku.toUpperCase()), quantity)))));
    }

    @Entonces("inventario informa la salida del despacho {string}")
    public void deductionReported(String dispatch) {
        JsonNode payload = awaitOutbox("StockDeducted", session.id(dispatch));
        assertThat(payload.path("dispatchId").asText()).isEqualTo(session.id(dispatch).toString());
    }

    @Cuando("la obra registra en parcial {string} del material {string} del despacho {string}")
    public void partiallyReceived(String quantity, String sku, String dispatch) throws Exception {
        reception("ReceptionPartiallyReceived", dispatch, quantity, sku);
    }

    @Cuando("la obra confirma la recepción del despacho {string} con {string} más del material {string}")
    public void confirmed(String dispatch, String delta, String sku) throws Exception {
        reception("ReceptionConfirmed", dispatch, delta, sku);
    }

    @Cuando("se cierra el pedido {string}")
    public void orderClosed(String order) throws Exception {
        UUID orderId = session.id(order);
        publish(orderId, TestEvents.envelope(json, "OrderClosed", organization(), "ORDER", orderId,
                TestEvents.orderEnded(orderId, "Recepción completa")));
    }

    @Entonces("inventario libera {string} del pedido {string}")
    public void released(String quantity, String order) {
        JsonNode payload = awaitOutbox("StockReleased", session.id(order));
        assertThat(payload.path("reason").asText()).isEqualTo("ORDER_CLOSED");
        assertThat(payload.at("/lines/0/quantity").decimalValue()).isEqualByComparingTo(quantity);
    }

    private void reception(String eventType, String dispatch, String delta, String sku) throws Exception {
        UUID receptionId = receptionOf(dispatch);
        publish(receptionId, TestEvents.envelope(json, eventType, organization(), "RECEPTION", receptionId,
                TestEvents.reception(receptionId, session.id(dispatch), session.id(dispatch + "/obra"),
                        List.of(TestEvents.receivedLine(session.id(sku.toUpperCase()), delta)))));
    }

    private UUID receptionOf(String dispatch) {
        String key = dispatch + "/recepción";
        try {
            return session.id(key);
        } catch (AssertionError notYet) {
            UUID id = UUID.randomUUID();
            session.put(key, id);
            return id;
        }
    }

    private JsonNode awaitOutbox(String eventType, UUID aggregateId) {
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> outboxCount(eventType, aggregateId) > 0);
        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM outbox_events WHERE event_type = ? AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1""", String.class, eventType, aggregateId.toString());
        try {
            return json.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long outboxCount(String eventType, UUID aggregateId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, aggregateId.toString());
        return count == null ? 0 : count;
    }

    private void publish(UUID key, String envelope) throws Exception {
        kafka.send(Topics.ORDERING, key.toString(), envelope).get();
    }

    private UUID organization() {
        return session.id(ScenarioSession.ORGANIZATION);
    }

    private JsonNode stockItem(String sku, String warehouse) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock").param("warehouseId", session.id(warehouse).toString()),
                null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        JsonNode found = null;
        for (JsonNode item : session.read(response)) {
            if (item.path("sku").asText().equalsIgnoreCase(sku)) {
                found = item;
            }
        }
        assertThat(found).as("stock de %s en %s", sku, warehouse).isNotNull();
        return found;
    }
}
