package pe.buildshield.inventory.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.buildshield.inventory.shared.correlation.CorrelationId;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Escribe un evento en la tabla {@code outbox_events} dentro de la transacción del cambio de negocio
 * (Transactional Outbox): si el negocio hace rollback, el evento tampoco existe. Lo envía después
 * {@link OutboxPublisher}.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Organización y actor del {@code TenantContext} de la operación en curso. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, String aggregateType, Object aggregateId, Object payload) {
        TenantInfo tenant = TenantContext.require();
        UUID actor = TenantContext.SYSTEM_USER_ID.equals(tenant.userId()) ? null : tenant.userId();
        return append(tenant.organizationId(), actor, eventType, aggregateType, aggregateId, payload);
    }

    /** Organización y actor explícitos (por ejemplo, al registrar una organización nueva). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID organizationId, UUID actorId, String eventType, String aggregateType, Object aggregateId,
            Object payload) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        // La tabla tiene las columnas del ER de la v4 (sin aggregate_type ni actor_id): el sobre completo
        // va en payload y el publicador lo envía tal cual.
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, occurredAt, organizationId, aggregateType,
                aggregateId.toString(), actorId, EventEnvelope.CURRENT_VERSION, json.valueToTree(payload));
        jdbc.update("""
                INSERT INTO outbox_events (id, organization_id, aggregate_id, event_type, payload, occurred_at,
                    correlation_id)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, eventId, organizationId, UUID.fromString(aggregateId.toString()), eventType,
                json.valueToTree(envelope).toString(), Timestamp.from(occurredAt), MDC.get(CorrelationId.MDC_KEY));
        return eventId;
    }
}
