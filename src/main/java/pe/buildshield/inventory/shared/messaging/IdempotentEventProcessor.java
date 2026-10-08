package pe.buildshield.inventory.shared.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.inventory.shared.correlation.CorrelationId;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Consumo idempotente: en una sola transacción registra el {@code eventId} en {@code processed_events}
 * y aplica el efecto. Si el evento ya estaba registrado lo ignora. Si el efecto falla, la transacción
 * se revierte (el evento tampoco queda registrado) y el contenedor de Kafka lo reintenta.
 *
 * <p>La transacción y el efecto corren con la organización del evento en el {@code TenantContext} (rol {@value #SYSTEM_ROLE})
 * y con el identificador de correlación que trajo el mensaje.
 */
public class IdempotentEventProcessor {

    /** Rol con el que se procesan los eventos: no es un rol de usuario. */
    public static final String SYSTEM_ROLE = "SYSTEM";

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventProcessor.class);

    private final ProcessedEvents processedEvents;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper json;

    public IdempotentEventProcessor(ProcessedEvents processedEvents, TransactionTemplate transactionTemplate,
            ObjectMapper json) {
        this.processedEvents = processedEvents;
        this.transactionTemplate = transactionTemplate;
        this.json = json;
    }

    /** @return {@code true} si se procesó; {@code false} si era un duplicado */
    public boolean process(ConsumerRecord<String, String> record, Consumer<EventEnvelope> handler) {
        Header correlation = record.headers().lastHeader(CorrelationId.HEADER);
        String correlationId = correlation == null ? null : new String(correlation.value(), StandardCharsets.UTF_8);
        return process(record.value(), correlationId, handler);
    }

    public boolean process(String message, String correlationId, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = parse(message);
        TenantInfo tenant = new TenantInfo(envelope.organizationId(),
                envelope.actorId() == null ? TenantContext.SYSTEM_USER_ID : envelope.actorId(), SYSTEM_ROLE);
        boolean[] processed = {false};
        // La organización se fija antes de abrir la transacción: el filtro de Hibernate se activa al abrir la sesión.
        CorrelationId.runWith(correlationId, () -> TenantContext.runAs(tenant, () -> processed[0] = Boolean.TRUE.equals(
                transactionTemplate.execute(status -> {
                    if (!processedEvents.markProcessed(envelope.eventId())) {
                        log.debug("Evento {} {} ya procesado: se ignora", envelope.eventType(), envelope.eventId());
                        return false;
                    }
                    handler.accept(envelope);
                    return true;
                }))));
        return processed[0];
    }

    public EventEnvelope parse(String message) {
        try {
            EventEnvelope envelope = json.readValue(message, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null || envelope.organizationId() == null) {
                throw new IllegalArgumentException("Sobre de evento incompleto");
            }
            return envelope;
        } catch (IOException ex) {
            throw new IllegalArgumentException("El mensaje no es un sobre de evento válido", ex);
        }
    }
}
