package pe.buildshield.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lectura de los campos del payload de un evento. Un campo obligatorio ausente o mal formado lanza
 * {@link IllegalArgumentException}: el evento no se reintenta (no es un sobre válido para este servicio).
 */
final class Payloads {

    private Payloads() {
    }

    static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Falta el campo " + field + " en el payload");
        }
        return UUID.fromString(value.asText());
    }

    /** Campo opcional: nulo si falta o está vacío. */
    static UUID optionalUuid(JsonNode payload, String field) {
        String value = text(payload, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    static String text(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asText() : null;
    }

    static BigDecimal decimal(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Falta el campo " + field + " en el payload");
        }
        return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
    }

    static <T> List<T> list(JsonNode payload, String field, Function<JsonNode, T> mapper) {
        List<T> items = new ArrayList<>();
        for (JsonNode item : payload.path(field)) {
            items.add(mapper.apply(item));
        }
        return items;
    }
}
