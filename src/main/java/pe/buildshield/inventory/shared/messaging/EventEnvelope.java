package pe.buildshield.inventory.shared.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Sobre JSON de todo evento de integración:
 * {@code {"eventId","eventType","occurredAt","organizationId","aggregateType","aggregateId","actorId","version":1,"payload":{...}}}.
 *
 * @param eventId identificador único; los consumidores lo usan para no procesar dos veces
 * @param actorId usuario que provocó el cambio; nulo si fue un proceso del sistema
 * @param version versión del formato del payload (empieza en 1)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt, UUID organizationId,
        String aggregateType, String aggregateId, UUID actorId, int version, JsonNode payload) {

    public static final int CURRENT_VERSION = 1;
}
