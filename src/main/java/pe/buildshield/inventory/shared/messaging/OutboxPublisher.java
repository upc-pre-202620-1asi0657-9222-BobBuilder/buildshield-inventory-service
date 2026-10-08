package pe.buildshield.inventory.shared.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.inventory.shared.correlation.CorrelationId;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Envía a Kafka los eventos pendientes de {@code outbox_events}, en orden de ocurrencia, con el
 * {@code aggregateId} como clave (mismo agregado, misma partición) y el sobre {@link EventEnvelope}
 * como valor. Marca {@code published_at} solo después de la confirmación del broker: entrega al
 * menos una vez (un reintento puede duplicar; los consumidores son idempotentes).
 *
 * <p>Cada servicio declara su publicador ({@code <X>OutboxPublisher}) heredando de esta clase y
 * llamando a {@link #publishPending()} con {@code @Scheduled} cada 500 ms. {@code FOR UPDATE SKIP LOCKED}
 * permite varias réplicas del servicio sin enviar dos veces el mismo lote.
 */
public class OutboxPublisher {

    /** Cabecera Kafka con el tipo de evento, para filtrar sin leer el valor. */
    public static final String EVENT_TYPE_HEADER = "eventType";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String topic;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            TransactionTemplate transactionTemplate, Clock clock, String topic, int batchSize, Duration sendTimeout) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("Falta buildshield.messaging.topic: el tópico donde publica el servicio");
        }
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    public String topic() {
        return topic;
    }

    /** @return cuántos eventos se enviaron en esta pasada */
    public int publishPending() {
        try {
            Integer sent = transactionTemplate.execute(status -> {
                List<PendingEvent> pending = jdbc.query("""
                        SELECT id, event_type, aggregate_type, aggregate_id, organization_id, actor_id, correlation_id,
                               payload, occurred_at
                        FROM outbox_events WHERE published_at IS NULL
                        ORDER BY occurred_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                        """, (rs, row) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                        rs.getObject("organization_id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("correlation_id"), rs.getString("payload"),
                        rs.getTimestamp("occurred_at").toInstant()), batchSize);
                for (PendingEvent event : pending) {
                    send(event);
                    jdbc.update("UPDATE outbox_events SET published_at = ? WHERE id = ?",
                            Timestamp.from(clock.instant()), event.id());
                }
                return pending.size();
            });
            return sent == null ? 0 : sent;
        } catch (RuntimeException ex) {
            // Kafka o la base no disponibles: los eventos siguen pendientes y se reintentan en la próxima pasada.
            log.warn("No se pudieron publicar los eventos pendientes del outbox: {}", ex.getMessage());
            return 0;
        }
    }

    private void send(PendingEvent event) {
        String value;
        try {
            value = json.writeValueAsString(new EventEnvelope(event.id(), event.eventType(), event.occurredAt(),
                    event.organizationId(), event.aggregateType(), event.aggregateId(), event.actorId(),
                    EventEnvelope.CURRENT_VERSION, json.readTree(event.payload())));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Evento " + event.id() + " con payload inválido", ex);
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.aggregateId(), value);
        if (event.correlationId() != null) {
            record.headers().add(CorrelationId.HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add(EVENT_TYPE_HEADER, event.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envío interrumpido", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka no confirmó el evento " + event.id() + ": " + ex.getMessage(), ex);
        }
    }

    private record PendingEvent(UUID id, String eventType, String aggregateType, String aggregateId,
            UUID organizationId, UUID actorId, String correlationId, String payload, Instant occurredAt) {
    }
}
