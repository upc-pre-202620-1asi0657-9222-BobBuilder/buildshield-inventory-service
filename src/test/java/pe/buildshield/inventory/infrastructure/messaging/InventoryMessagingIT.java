package pe.buildshield.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.inventory.application.StockCommandService;
import pe.buildshield.inventory.shared.messaging.Topics;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InventoryIntegrationTest;
import pe.buildshield.inventory.support.KafkaConsumersReady;
import pe.buildshield.inventory.support.TestDatabase;
import pe.buildshield.inventory.support.TestEvents;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga de reserva de punta a punta dentro de inventory, con Kafka embebido y PostgreSQL real: los eventos de
 * ordering entran por {@code buildshield.ordering}, el stock cambia y la respuesta sale por el outbox a
 * {@code buildshield.inventory} con el sobre común. Un evento repetido (mismo {@code eventId}) se procesa una vez.
 */
@InventoryIntegrationTest
class InventoryMessagingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    InventoryOutboxPublisher publisher;

    @Autowired
    StockCommandService commands;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private UUID organization;
    private UUID warehouse;
    private UUID worksite;
    private UUID cement;
    private UUID steel;
    private Consumer<String, String> inventoryTopic;

    @BeforeEach
    void catalog() {
        KafkaConsumersReady.awaitAssignment(listeners);
        TestDatabase.reset(jdbc);
        organization = UUID.randomUUID();
        warehouse = UUID.randomUUID();
        worksite = UUID.randomUUID();
        cement = UUID.randomUUID();
        steel = UUID.randomUUID();
        publishOrganization(TestEvents.location(json, "LocationRegistered", organization, warehouse, "WAREHOUSE",
                "Almacén Central", true));
        publishOrganization(TestEvents.location(json, "LocationRegistered", organization, worksite, "WORKSITE",
                "Torre Norte", true));
        publishOrganization(TestEvents.material(json, "MaterialRegistered", organization, cement, "CEM-001",
                "Cemento", "BAG", "2.5", true));
        publishOrganization(TestEvents.material(json, "MaterialRegistered", organization, steel, "ACE-012",
                "Acero", "KG", "1", true));
        await().atMost(TIMEOUT).until(() -> count("SELECT count(*) FROM material_projection WHERE organization_id = ?") == 2
                && count("SELECT count(*) FROM location_projection WHERE organization_id = ?") == 2);
        TenantInfo admin = new TenantInfo(organization, UUID.randomUUID(), "ADMINISTRATOR");
        TenantContext.runAs(admin, () -> {
            commands.registerEntry(warehouse, cement, new BigDecimal("100"), "Guía 1");
            commands.registerEntry(warehouse, steel, new BigDecimal("10"), "Guía 2");
        });
        inventoryTopic = consumer();
    }

    @AfterEach
    void close() {
        inventoryTopic.close();
    }

    @Test
    void organization_events_feed_the_projections_and_later_changes_replace_them() {
        UUID rosa = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        publishOrganization(TestEvents.assignment(json, "StaffAssigned", organization, assignment, rosa, warehouse,
                "WAREHOUSE"));
        await().atMost(TIMEOUT).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM assignment_projection WHERE assignment_id = ?", Boolean.class, assignment)));
        publishOrganization(TestEvents.assignment(json, "StaffUnassigned", organization, assignment, rosa, warehouse,
                "WAREHOUSE"));
        publishOrganization(TestEvents.material(json, "MaterialUpdated", organization, cement, "CEM-001",
                "Cemento Portland", "BAG", "3", false));
        publishOrganization(TestEvents.location(json, "LocationUpdated", organization, warehouse, "WAREHOUSE",
                "Almacén Central Norte", false));

        await().atMost(TIMEOUT).until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT active FROM assignment_projection WHERE assignment_id = ?", Boolean.class, assignment)));
        await().atMost(TIMEOUT).until(() -> "Almacén Central Norte".equals(jdbc.queryForObject(
                "SELECT name FROM location_projection WHERE location_id = ?", String.class, warehouse)));
        await().atMost(TIMEOUT).until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT active FROM material_projection WHERE material_id = ?", Boolean.class, cement)));
        assertThat(jdbc.queryForObject("SELECT tolerance_percent FROM material_projection WHERE material_id = ?",
                BigDecimal.class, cement)).isEqualByComparingTo("3");
    }

    @Test
    void the_whole_saga_reserves_deducts_receives_and_releases() throws Exception {
        UUID order = UUID.randomUUID();
        UUID cementLine = UUID.randomUUID();
        UUID steelLine = UUID.randomUUID();

        // 1-2. OrderApproved → reserva todo o nada → StockReserved.
        publishOrdering("OrderApproved", "ORDER", order, TestEvents.orderApproved(order, warehouse, worksite, List.of(
                TestEvents.orderLine(cementLine, cement, "50"), TestEvents.orderLine(steelLine, steel, "10"))));
        JsonNode reserved = awaitInventoryEvent("StockReserved", order);
        assertThat(reserved.get("aggregateType").asText()).isEqualTo("ORDER");
        assertThat(reserved.get("version").asInt()).isEqualTo(1);
        assertThat(reserved.at("/payload/orderId").asText()).isEqualTo(order.toString());
        assertThat(reserved.at("/payload/warehouseId").asText()).isEqualTo(warehouse.toString());
        assertThat(reserved.at("/payload/reservations")).hasSize(2);
        assertThat(reserved.at("/payload/reservations/0/orderLineId").asText()).isEqualTo(cementLine.toString());
        assertThat(reserved.at("/payload/reservations/0/materialId").asText()).isEqualTo(cement.toString());
        assertThat(reserved.at("/payload/reservations/0/quantity").decimalValue()).isEqualByComparingTo("50");
        assertThat(level(warehouse, cement, "available_qty")).isEqualByComparingTo("50");
        assertThat(level(warehouse, cement, "reserved_qty")).isEqualByComparingTo("50");

        // 4. DispatchDeparted → consume la reserva → StockDeducted.
        UUID dispatch = UUID.randomUUID();
        publishOrdering("DispatchDeparted", "DISPATCH", dispatch, TestEvents.dispatchDeparted(dispatch, order,
                warehouse, worksite, List.of(TestEvents.orderLine(cementLine, cement, "30"),
                        TestEvents.orderLine(steelLine, steel, "10"))));
        JsonNode deducted = awaitInventoryEvent("StockDeducted", dispatch);
        assertThat(deducted.at("/payload/orderId").asText()).isEqualTo(order.toString());
        assertThat(deducted.at("/payload/lines/0/availableAfter").decimalValue()).isEqualByComparingTo("50");
        assertThat(deducted.at("/payload/lines/0/reservedAfter").decimalValue()).isEqualByComparingTo("20");
        assertThat(level(warehouse, cement, "reserved_qty")).isEqualByComparingTo("20");
        assertThat(level(warehouse, steel, "reserved_qty")).isZero();

        // 5. Parcial y conformidad → la obra suma solo el delta → StockReceived.
        UUID reception = UUID.randomUUID();
        publishOrdering("ReceptionPartiallyReceived", "RECEPTION", reception, TestEvents.reception(reception, dispatch,
                worksite, List.of(TestEvents.receivedLine(cement, "20"))));
        JsonNode partial = awaitInventoryEvent("StockReceived", reception);
        assertThat(partial.at("/payload/source").asText()).isEqualTo("PARTIAL");
        assertThat(partial.at("/payload/worksiteId").asText()).isEqualTo(worksite.toString());
        publishOrdering("ReceptionConfirmed", "RECEPTION", reception, TestEvents.reception(reception, dispatch,
                worksite, List.of(TestEvents.receivedLine(cement, "9.5"), TestEvents.receivedLine(steel, "10"))));
        await().atMost(TIMEOUT).until(() -> {
            publisher.publishPending();
            return level(worksite, steel, "available_qty").compareTo(BigDecimal.TEN) == 0;
        });
        assertThat(level(worksite, cement, "available_qty")).isEqualByComparingTo("29.5");

        // 6. OrderClosed → libera lo reservado sin despachar → StockReleased.
        publishOrdering("OrderClosed", "ORDER", order, TestEvents.orderEnded(order, "Recepción completa"));
        JsonNode released = awaitInventoryEvent("StockReleased", order);
        assertThat(released.at("/payload/reason").asText()).isEqualTo("ORDER_CLOSED");
        assertThat(released.at("/payload/lines/0/quantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(level(warehouse, cement, "available_qty")).isEqualByComparingTo("70");
        assertThat(level(warehouse, cement, "reserved_qty")).isZero();
    }

    @Test
    void an_order_without_enough_stock_is_rejected_with_what_is_missing_and_nothing_is_reserved() throws Exception {
        UUID order = UUID.randomUUID();
        publishOrdering("OrderApproved", "ORDER", order, TestEvents.orderApproved(order, warehouse, worksite, List.of(
                TestEvents.orderLine(UUID.randomUUID(), cement, "50"),
                TestEvents.orderLine(UUID.randomUUID(), steel, "25"))));

        JsonNode rejected = awaitInventoryEvent("StockReservationRejected", order);

        assertThat(rejected.at("/payload/orderId").asText()).isEqualTo(order.toString());
        assertThat(rejected.at("/payload/reason").asText())
                .isEqualTo("Stock insuficiente en el almacén para ACE-012 (hay 10, se pidió 25)");
        assertThat(rejected.at("/payload/missing")).hasSize(1);
        assertThat(rejected.at("/payload/missing/0/materialId").asText()).isEqualTo(steel.toString());
        assertThat(rejected.at("/payload/missing/0/requested").decimalValue()).isEqualByComparingTo("25");
        assertThat(rejected.at("/payload/missing/0/available").decimalValue()).isEqualByComparingTo("10");
        assertThat(level(warehouse, cement, "reserved_qty")).isZero();
        assertThat(count("SELECT count(*) FROM stock_reservations WHERE organization_id = ?")).isZero();
    }

    @Test
    void a_duplicated_event_is_processed_once() throws Exception {
        UUID order = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String approved = TestEvents.envelope(json, eventId, "OrderApproved", organization, "ORDER", order,
                TestEvents.orderApproved(order, warehouse, worksite, List.of(
                        TestEvents.orderLine(UUID.randomUUID(), cement, "40"))));

        kafka.send(Topics.ORDERING, order.toString(), approved).get();
        kafka.send(Topics.ORDERING, order.toString(), approved).get();
        awaitInventoryEvent("StockReserved", order);
        // Marca en la misma partición (misma clave): cuando se procesa, el duplicado ya pasó por el consumidor.
        UUID marker = UUID.randomUUID();
        kafka.send(Topics.ORDERING, order.toString(), TestEvents.envelope(json, marker, "OrderRegistered", organization,
                "ORDER", order, Map.of("orderId", order))).get();
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, marker) == 1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(level(warehouse, cement, "reserved_qty")).isEqualByComparingTo("40");
        assertThat(count("SELECT count(*) FROM stock_reservations WHERE organization_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE organization_id = ? AND event_type = 'StockReserved'"))
                .isEqualTo(1);
    }

    private void publishOrganization(String envelope) {
        try {
            kafka.send(Topics.ORGANIZATION, json.readTree(envelope).get("aggregateId").asText(), envelope).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void publishOrdering(String eventType, String aggregateType, UUID aggregateId, Map<String, Object> payload)
            throws Exception {
        kafka.send(Topics.ORDERING, aggregateId.toString(),
                TestEvents.envelope(json, eventType, organization, aggregateType, aggregateId, payload)).get();
    }

    private JsonNode awaitInventoryEvent(String eventType, UUID aggregateId) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        Callable<Boolean> arrived = () -> {
            publisher.publishPending();
            for (ConsumerRecord<String, String> record : inventoryTopic.poll(Duration.ofMillis(300))) {
                JsonNode envelope = json.readTree(record.value());
                if (envelope.get("eventType").asText().equals(eventType)
                        && envelope.get("aggregateId").asText().equals(aggregateId.toString())) {
                    assertThat(record.key()).isEqualTo(aggregateId.toString());
                    assertThat(envelope.get("organizationId").asText()).isEqualTo(organization.toString());
                    found.add(envelope);
                }
            }
            return !found.isEmpty();
        };
        await().atMost(TIMEOUT).until(arrived);
        return found.get(0);
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "inventory-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
        consumer.subscribe(List.of(Topics.INVENTORY));
        return consumer;
    }

    private BigDecimal level(UUID location, UUID material, String column) {
        List<BigDecimal> values = jdbc.queryForList("SELECT " + column + " FROM stock_items WHERE organization_id = ? "
                + "AND location_id = ? AND material_id = ?", BigDecimal.class, organization, location, material);
        return values.isEmpty() ? BigDecimal.ZERO : values.get(0);
    }

    private long count(String sql) {
        Long value = sql.contains("?") ? jdbc.queryForObject(sql, Long.class, organization)
                : jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
