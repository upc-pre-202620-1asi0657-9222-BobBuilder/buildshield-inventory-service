package pe.buildshield.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import pe.buildshield.inventory.infrastructure.persistence.JdbcOrganizationProjections;
import pe.buildshield.inventory.shared.messaging.EventEnvelope;
import pe.buildshield.inventory.shared.messaging.IdempotentEventProcessor;

import java.math.BigDecimal;

import static pe.buildshield.inventory.infrastructure.messaging.Payloads.text;
import static pe.buildshield.inventory.infrastructure.messaging.Payloads.uuid;

/**
 * Consume {@code buildshield.organization} y mantiene las copias locales (plan §7):
 *
 * <ul>
 *   <li>{@code LocationRegistered} y {@code LocationUpdated}: obras y almacenes (nombre, tipo, activo).</li>
 *   <li>{@code MaterialRegistered} y {@code MaterialUpdated}: catálogo (SKU, nombre, unidad, tolerancia).</li>
 *   <li>{@code StaffAssigned} y {@code StaffUnassigned}: qué encargado ve qué obra o almacén.</li>
 * </ul>
 *
 * Cada evento se procesa una sola vez ({@link IdempotentEventProcessor}); los demás tipos se ignoran.
 */
@Component
public class OrganizationEventConsumer {

    static final String LOCATION_REGISTERED = "LocationRegistered";
    static final String LOCATION_UPDATED = "LocationUpdated";
    static final String MATERIAL_REGISTERED = "MaterialRegistered";
    static final String MATERIAL_UPDATED = "MaterialUpdated";
    static final String STAFF_ASSIGNED = "StaffAssigned";
    static final String STAFF_UNASSIGNED = "StaffUnassigned";

    private static final Logger log = LoggerFactory.getLogger(OrganizationEventConsumer.class);

    private final IdempotentEventProcessor processor;
    private final JdbcOrganizationProjections projections;

    public OrganizationEventConsumer(IdempotentEventProcessor processor, JdbcOrganizationProjections projections) {
        this.processor = processor;
        this.projections = projections;
    }

    @KafkaListener(topics = "${buildshield.messaging.consumes.organization:buildshield.organization}")
    public void onMessage(ConsumerRecord<String, String> record) {
        processor.process(record, this::handle);
    }

    void handle(EventEnvelope event) {
        JsonNode payload = event.payload();
        switch (event.eventType()) {
            case LOCATION_REGISTERED, LOCATION_UPDATED -> projections.upsertLocation(event.organizationId(),
                    uuid(payload, "locationId"), text(payload, "type"), text(payload, "name"), text(payload, "address"),
                    payload.path("active").asBoolean(true));
            case MATERIAL_REGISTERED, MATERIAL_UPDATED -> projections.upsertMaterial(event.organizationId(),
                    uuid(payload, "materialId"), text(payload, "sku"), text(payload, "name"), text(payload, "unit"),
                    payload.hasNonNull("tolerancePercent") ? payload.get("tolerancePercent").decimalValue()
                            : BigDecimal.ZERO, payload.path("active").asBoolean(true));
            case STAFF_ASSIGNED, STAFF_UNASSIGNED -> projections.upsertAssignment(event.organizationId(),
                    uuid(payload, "assignmentId"), uuid(payload, "userId"), uuid(payload, "locationId"),
                    text(payload, "locationType"), STAFF_ASSIGNED.equals(event.eventType()));
            default -> log.debug("Evento {} ignorado por inventory", event.eventType());
        }
    }
}
