package pe.buildshield.inventory.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sobres de los eventos que inventory consume, tal como los publican organization y ordering (nombres de campos
 * de wiki/informe-tf/notas-etapa-b1.md). Las pruebas los envían a Kafka embebido.
 */
public final class TestEvents {

    private TestEvents() {
    }

    /** Sobre con un {@code eventId} nuevo. */
    public static String envelope(ObjectMapper json, String eventType, UUID organizationId, String aggregateType,
            UUID aggregateId, Map<String, Object> payload) {
        return envelope(json, UUID.randomUUID(), eventType, organizationId, aggregateType, aggregateId, payload);
    }

    public static String envelope(ObjectMapper json, UUID eventId, String eventType, UUID organizationId,
            String aggregateType, UUID aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("organizationId", organizationId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("actorId", null);
        envelope.put("version", 1);
        envelope.put("payload", payload);
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------- buildshield.organization ----------

    public static String location(ObjectMapper json, String eventType, UUID organizationId, UUID locationId,
            String type, String name, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("locationId", locationId);
        payload.put("type", type);
        payload.put("name", name);
        payload.put("address", "Av. Principal 100");
        payload.put("active", active);
        return envelope(json, eventType, organizationId, "LOCATION", locationId, payload);
    }

    public static String material(ObjectMapper json, String eventType, UUID organizationId, UUID materialId,
            String sku, String name, String unit, String tolerance, boolean active) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("materialId", materialId);
        payload.put("sku", sku);
        payload.put("name", name);
        payload.put("unit", unit);
        payload.put("tolerancePercent", new BigDecimal(tolerance));
        payload.put("active", active);
        return envelope(json, eventType, organizationId, "MATERIAL", materialId, payload);
    }

    public static String assignment(ObjectMapper json, String eventType, UUID organizationId, UUID assignmentId,
            UUID userId, UUID locationId, String locationType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignmentId);
        payload.put("userId", userId);
        payload.put("locationId", locationId);
        payload.put("locationType", locationType);
        return envelope(json, eventType, organizationId, "ASSIGNMENT", assignmentId, payload);
    }

    // ---------- buildshield.ordering ----------

    /** Línea {orderLineId, materialId, quantity} de OrderApproved y DispatchDeparted. */
    public static Map<String, Object> orderLine(UUID orderLineId, UUID materialId, String quantity) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("orderLineId", orderLineId);
        line.put("materialId", materialId);
        line.put("quantity", new BigDecimal(quantity));
        return line;
    }

    public static Map<String, Object> orderApproved(UUID orderId, UUID warehouseId, UUID worksiteId,
            List<Map<String, Object>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("warehouseId", warehouseId);
        payload.put("worksiteId", worksiteId);
        payload.put("approvedBy", UUID.randomUUID());
        payload.put("lines", lines);
        return payload;
    }

    public static Map<String, Object> dispatchDeparted(UUID dispatchId, UUID orderId, UUID warehouseId,
            UUID worksiteId, List<Map<String, Object>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dispatchId", dispatchId);
        payload.put("orderId", orderId);
        payload.put("manifestCode", "MAN-20261103-7KQ2M9XA");
        payload.put("warehouseId", warehouseId);
        payload.put("worksiteId", worksiteId);
        payload.put("departedAt", Instant.now().toString());
        payload.put("lines", lines);
        return payload;
    }

    /** Línea de recepción: {receptionLineId, materialId, deltaQuantity} (más campos que inventory ignora). */
    public static Map<String, Object> receivedLine(UUID materialId, String deltaQuantity) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("receptionLineId", UUID.randomUUID());
        line.put("materialId", materialId);
        line.put("deltaQuantity", new BigDecimal(deltaQuantity));
        return line;
    }

    public static Map<String, Object> reception(UUID receptionId, UUID dispatchId, UUID worksiteId,
            List<Map<String, Object>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receptionId", receptionId);
        payload.put("dispatchId", dispatchId);
        payload.put("worksiteId", worksiteId);
        payload.put("lines", lines);
        return payload;
    }

    public static Map<String, Object> orderEnded(UUID orderId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("reason", reason);
        return payload;
    }
}
