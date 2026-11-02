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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.buildshield.inventory.acceptance.ApiRequest.get;
import static pe.buildshield.inventory.acceptance.ApiRequest.post;
import static pe.buildshield.inventory.acceptance.IdentitySteps.EVENT_TIMEOUT;

/**
 * Pasos del Sprint 4 de inventario: inventario inicial, reposiciones e historial (US40), conteo de validación y
 * ajuste (US41) y devolución de saldos de obra (US43). Todo por HTTP real; los eventos publicados se leen del outbox.
 */
public class CountAndReturnSteps {

    private static final String RETURN = "__devolución__";

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    // ---------- US40: inventario inicial, reposiciones e historial ----------

    @Cuando("registro el inventario inicial de {string} del material {string} en el almacén {string}")
    public void registerInitial(String quantity, String sku, String warehouse) throws Exception {
        entry(quantity, sku, warehouse, "INITIAL", null);
    }

    @Cuando("registro una reposición de {string} del material {string} en el almacén {string} con la guía {string}")
    public void registerReplenishment(String quantity, String sku, String warehouse, String note) throws Exception {
        entry(quantity, sku, warehouse, "REPLENISHMENT", note);
    }

    @Entonces("el historial del almacén {string} muestra los movimientos {string}")
    public void historyShows(String warehouse, String types) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock/movements/history")
                .param("locationId", session.id(warehouse).toString()), null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        List<String> found = new ArrayList<>();
        session.read(response).path("items").forEach(item -> found.add(item.path("type").asText()));
        assertThat(found).containsExactlyElementsOf(Arrays.stream(types.split(",")).map(String::trim).toList());
    }

    @Entonces("el historial del almacén {string} empieza con el movimiento {string}")
    public void historyStartsWith(String warehouse, String type) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock/movements/history")
                .param("locationId", session.id(warehouse).toString()).param("size", "1"), null,
                session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        assertThat(session.read(response).at("/items/0/type").asText()).isEqualTo(type);
    }

    @Entonces("inventario publicó la entrada {string} del material {string}")
    public void entryPublished(String entryType, String sku) {
        String materialId = session.id(sku.toUpperCase()).toString();
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE event_type = 'StockEntryRegistered'
                AND payload -> 'payload' ->> 'entryType' = ? AND payload -> 'payload' ->> 'materialId' = ?""", Long.class, entryType,
                materialId) > 0);
    }

    @Cuando("consulto la página {int} de tamaño {int} del historial del almacén {string}")
    public void historyPage(int page, int size, String warehouse) throws Exception {
        session.send(get("/api/v1/stock/movements/history").param("locationId", session.id(warehouse).toString())
                .param("page", String.valueOf(page)).param("size", String.valueOf(size)), null);
    }

    @Cuando("consulto el historial de movimientos {string} del almacén {string}")
    public void historyOfType(String type, String warehouse) throws Exception {
        session.send(get("/api/v1/stock/movements/history").param("locationId", session.id(warehouse).toString())
                .param("type", type), null);
    }

    @Entonces("la página tiene {int} movimientos de un total de {int}")
    public void pageHas(int items, int total) throws Exception {
        session.expectStatus(200);
        assertThat(session.body().path("items").size()).isEqualTo(items);
        assertThat(session.body().path("total").asLong()).isEqualTo(total);
    }

    // ---------- US41: conteo físico y ajuste ----------

    @Cuando("comparo lo contado en el almacén {string}: {string} del material {string} y {string} del material {string}")
    public void compare(String warehouse, String first, String firstSku, String second, String secondSku)
            throws Exception {
        session.send(post("/api/v1/stock/counts/comparison"), countBody(warehouse, first, firstSku, second, secondSku,
                null));
    }

    @Cuando("registro lo contado en el almacén {string}: {string} del material {string} y {string} del material {string}")
    public void registerWithoutReason(String warehouse, String first, String firstSku, String second, String secondSku)
            throws Exception {
        session.send(post("/api/v1/stock/counts"), countBody(warehouse, first, firstSku, second, secondSku, null));
    }

    @Cuando("ajusto lo contado en el almacén {string}: {string} del material {string} y {string} del material {string} con el motivo {string}")
    public void adjust(String warehouse, String first, String firstSku, String second, String secondSku, String reason)
            throws Exception {
        session.send(post("/api/v1/stock/counts"), countBody(warehouse, first, firstSku, second, secondSku, reason));
    }

    @Entonces("el conteo queda en estado {string} con {int} diferencias")
    public void countStatus(String status, int differences) throws Exception {
        assertThat(session.status()).isIn(200, 201);
        assertThat(session.body().path("status").asText()).isEqualTo(status);
        assertThat(session.body().path("differenceCount").asInt()).isEqualTo(differences);
    }

    @Entonces("el almacén {string} tiene {int} ajustes guardados y el del material {string} dice contado {string}, sistema {string}")
    public void adjustmentsStored(String warehouse, int total, String sku, String counted, String system)
            throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock/counts")
                .param("warehouseId", session.id(warehouse).toString()), null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        JsonNode list = session.read(response);
        assertThat(list.size()).isEqualTo(total);
        JsonNode found = null;
        for (JsonNode item : list) {
            if (item.path("sku").asText().equals(sku)) {
                found = item;
            }
        }
        assertThat(found).as("ajuste de %s", sku).isNotNull();
        assertThat(found.path("countedQty").decimalValue()).isEqualByComparingTo(counted);
        assertThat(found.path("systemQty").decimalValue()).isEqualByComparingTo(system);
        assertThat(session.read(session.perform(get("/api/v1/stock/counts/" + found.path("id").asText()), null,
                session.accessToken())).path("sku").asText()).isEqualTo(sku);
    }

    @Entonces("el almacén {string} no tiene ajustes guardados")
    public void noAdjustmentsStored(String warehouse) throws Exception {
        ApiResponse response = session.perform(get("/api/v1/stock/counts")
                .param("warehouseId", session.id(warehouse).toString()), null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        assertThat(session.read(response).size()).isZero();
    }

    @Entonces("inventario publicó el ajuste del conteo con la diferencia {string} del material {string}")
    public void adjustmentPublished(String difference, String sku) throws Exception {
        String materialId = session.id(sku.toUpperCase()).toString();
        String sql = """
                SELECT (payload -> 'payload')::text FROM outbox_events WHERE event_type = 'StockAdjusted'
                AND payload -> 'payload' -> 'lines' -> 0 ->> 'materialId' = ? ORDER BY occurred_at DESC LIMIT 1""";
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> !jdbc.queryForList(sql, String.class, materialId)
                .isEmpty());
        JsonNode payload = json.readTree(jdbc.queryForObject(sql, String.class, materialId));
        assertThat(payload.at("/lines/0/difference").decimalValue()).isEqualByComparingTo(difference);
        assertThat(payload.path("reason").asText()).isNotBlank();
        assertThat(payload.path("countId").asText()).isNotBlank();
    }

    private Map<String, Object> countBody(String warehouse, String first, String firstSku, String second,
            String secondSku, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("warehouseId", session.id(warehouse).toString());
        body.put("lines", List.of(
                Map.of("materialId", session.id(firstSku.toUpperCase()).toString(), "countedQty", new BigDecimal(first)),
                Map.of("materialId", session.id(secondSku.toUpperCase()).toString(), "countedQty",
                        new BigDecimal(second))));
        body.put("reason", reason);
        return body;
    }

    // ---------- US43: saldos de obra y devolución ----------

    @Dado("que la obra {string} recibió {string} del material {string} desde el almacén {string}")
    public void worksiteReceived(String worksite, String quantity, String sku, String warehouse) throws Exception {
        // El origen sale de los movimientos: el almacén despacha y la obra recibe ese mismo despacho.
        entry(quantity, sku, warehouse, "INITIAL", null);
        session.expectStatus(201);
        UUID organization = session.id(ScenarioSession.ORGANIZATION);
        UUID dispatchId = UUID.randomUUID();
        kafka.send(Topics.ORDERING, dispatchId.toString(), TestEvents.envelope(json, "DispatchDeparted", organization,
                "DISPATCH", dispatchId, TestEvents.dispatchDeparted(dispatchId, UUID.randomUUID(), session.id(warehouse),
                        session.id(worksite), List.of(TestEvents.orderLine(UUID.randomUUID(),
                                session.id(sku.toUpperCase()), quantity))))).get();
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_movements WHERE source_ref = ?", Long.class, dispatchId) == 1);
        UUID receptionId = UUID.randomUUID();
        kafka.send(Topics.ORDERING, receptionId.toString(), TestEvents.envelope(json, "ReceptionConfirmed", organization,
                "RECEPTION", receptionId, TestEvents.reception(receptionId, dispatchId, session.id(worksite),
                        List.of(TestEvents.receivedLine(session.id(sku.toUpperCase()), quantity))))).get();
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_movements WHERE source_ref = ?", Long.class, dispatchId) == 2);
    }

    @Cuando("registro un saldo de {string} del material {string} de la obra {string} con el detalle {string}")
    public void registerBalance(String quantity, String sku, String worksite, String detail) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("materialId", session.id(sku.toUpperCase()).toString());
        line.put("quantity", new BigDecimal(quantity));
        line.put("detail", detail);
        session.send(post("/api/v1/stock/returns"), Map.of("worksiteId", session.id(worksite).toString(),
                "lines", List.of(line), "note", "Saldo de la obra"));
        if (session.status() == 201) {
            session.put(RETURN, UUID.fromString(session.body().path("id").asText()));
        }
    }

    @Entonces("la devolución queda en estado {string} hacia el almacén {string}")
    public void returnStatusTo(String status, String warehouse) throws Exception {
        session.expectStatus(201);
        assertThat(session.body().path("status").asText()).isEqualTo(status);
        assertThat(session.body().path("warehouseId").asText()).isEqualTo(session.id(warehouse).toString());
        assertThat(session.body().path("warehouseName").asText()).isEqualTo(warehouse);
    }

    @Cuando("solicito la devolución")
    public void requestReturn() throws Exception {
        returnStep("request", null);
    }

    @Cuando("apruebo la devolución")
    public void approveReturn() throws Exception {
        returnStep("approve", null);
    }

    @Cuando("rechazo la devolución por {string}")
    public void rejectReturn(String reason) throws Exception {
        returnStep("reject", Map.of("reason", reason));
    }

    @Cuando("inicio el retorno de la devolución")
    public void dispatchReturn() throws Exception {
        returnStep("dispatch", null);
    }

    @Cuando("verifico que acepto {string} del material {string} y rechazo el resto por {string}")
    public void receiveReturn(String accepted, String sku, String reason) throws Exception {
        returnStep("receive", Map.of("lines", List.of(Map.of("materialId", session.id(sku.toUpperCase()).toString(),
                "acceptedQty", new BigDecimal(accepted), "rejectionReason", reason))));
    }

    @Entonces("la devolución queda en estado {string} con rechazos")
    public void returnStatusWithRejections(String status) throws Exception {
        assertThat(session.body().path("status").asText()).isEqualTo(status);
        assertThat(session.body().path("hasRejections").asBoolean()).isTrue();
        ApiResponse detail = session.perform(get("/api/v1/stock/returns/" + session.id(RETURN)), null,
                session.accessToken());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(session.read(detail).path("status").asText()).isEqualTo(status);
    }

    @Entonces("inventario publicó {string} con {string} rechazado del material {string}")
    public void returnPublished(String eventType, String rejected, String sku) {
        JsonNode payload = awaitOutbox(eventType, session.id(RETURN));
        assertThat(payload.path("hasRejections").asBoolean()).isTrue();
        JsonNode line = payload.at("/lines/0");
        assertThat(line.path("materialId").asText()).isEqualTo(session.id(sku.toUpperCase()).toString());
        assertThat(line.path("rejectedQty").decimalValue()).isEqualByComparingTo(rejected);
        assertThat(line.path("rejectionReason").asText()).isNotBlank();
    }

    @Entonces("el saldo de la obra {string} permite devolver {string} del material {string} al almacén {string}")
    public void balanceAllows(String worksite, String returnable, String sku, String warehouse) throws Exception {
        session.expectStatus(201);
        ApiResponse response = session.perform(get("/api/v1/stock/worksites/" + session.id(worksite) + "/balance"),
                null, session.accessToken());
        assertThat(response.status()).isEqualTo(200);
        JsonNode line = session.read(response).at("/lines/0");
        assertThat(line.path("sku").asText()).isEqualTo(sku.toUpperCase());
        assertThat(line.path("returnableQty").decimalValue()).isEqualByComparingTo(returnable);
        assertThat(line.at("/originWarehouses/0/warehouseName").asText()).isEqualTo(warehouse);
    }

    private void returnStep(String action, Object body) throws Exception {
        session.send(post("/api/v1/stock/returns/" + session.id(RETURN) + "/" + action), body);
        session.expectStatus(200);
    }

    private void entry(String quantity, String sku, String warehouse, String type, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("warehouseId", session.id(warehouse).toString());
        body.put("materialId", session.id(sku.toUpperCase()).toString());
        body.put("quantity", new BigDecimal(quantity));
        body.put("entryType", type);
        body.put("note", note);
        session.send(post("/api/v1/stock/entries"), body);
    }

    private JsonNode awaitOutbox(String eventType, UUID aggregateId) {
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?", Long.class, eventType,
                aggregateId) > 0);
        String payload = jdbc.queryForObject("""
                SELECT (payload -> 'payload')::text FROM outbox_events WHERE event_type = ? AND aggregate_id = ?
                ORDER BY occurred_at DESC LIMIT 1""", String.class, eventType, aggregateId);
        try {
            return json.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
