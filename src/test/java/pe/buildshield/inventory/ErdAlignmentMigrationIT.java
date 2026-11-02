package pe.buildshield.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.inventory.support.InventoryIntegrationTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La migración V3 con PostgreSQL real: en un esquema temporal aplica V1 y V2, carga datos del esquema anterior y migra.
 * Comprueba los datos que se conservan y que el esquema final (el de la aplicación) es el del ERD corregido.
 */
@InventoryIntegrationTest
class ErdAlignmentMigrationIT {

    private static final String SCHEMA = "erd_migration_test";
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-11-01T10:00:00Z"));
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID WORKSITE = UUID.randomUUID();
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID WAREHOUSE_ITEM = UUID.randomUUID();
    private static final UUID WORKSITE_ITEM = UUID.randomUUID();

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void freshSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration").target(target).load();
    }

    private String t(String table) {
        return SCHEMA + "." + table;
    }

    @Test
    void the_final_schema_is_the_one_of_the_erd() {
        Map<String, Set<String>> expected = Map.ofEntries(
                Map.entry("stock_items", Set.of("id", "organization_id", "location_id", "material_id", "available_qty",
                        "reserved_qty", "version", "created_at", "created_by", "updated_at", "updated_by")),
                Map.entry("stock_reservations", Set.of("id", "organization_id", "stock_item_id", "order_id",
                        "order_line_id", "quantity", "status", "created_at")),
                Map.entry("stock_movements", Set.of("id", "organization_id", "stock_item_id", "type", "quantity",
                        "source_ref", "created_by", "created_at")),
                Map.entry("stock_adjustments", Set.of("id", "organization_id", "stock_item_id", "counted_qty",
                        "system_qty", "reason", "created_by", "created_at")),
                Map.entry("balance_returns", Set.of("id", "organization_id", "worksite_id", "destination_id", "status",
                        "return_dispatch_id", "created_by", "created_at")),
                Map.entry("balance_return_lines", Set.of("id", "organization_id", "balance_return_id", "material_id",
                        "declared_qty", "accepted_qty")),
                Map.entry("location_projection", Set.of("location_id", "organization_id", "type", "name", "active",
                        "updated_at")),
                Map.entry("material_projection", Set.of("material_id", "organization_id", "sku", "name", "unit",
                        "tolerance_percent", "active", "updated_at")),
                Map.entry("assignment_projection", Set.of("assignment_id", "organization_id", "user_id", "location_id",
                        "location_type", "updated_at")),
                Map.entry("outbox_events", Set.of("id", "organization_id", "aggregate_id", "event_type",
                        "correlation_id", "payload", "occurred_at", "published_at")),
                Map.entry("processed_events", Set.of("event_id", "consumer", "processed_at")));

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'""");
        Map<String, Set<String>> actual = columns.stream().collect(Collectors.groupingBy(
                row -> (String) row.get("table_name"),
                Collectors.mapping(row -> (String) row.get("column_name"), Collectors.toSet())));
        assertThat(actual).isEqualTo(expected);

        // Las FK son solo las del catálogo, de una columna.
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT rel.relname || '.' || att.attname || ' -> ' || ref.relname
                FROM pg_constraint c
                JOIN pg_class rel ON rel.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = rel.relnamespace
                JOIN pg_class ref ON ref.oid = c.confrelid
                JOIN unnest(c.conkey) AS key(attnum) ON true
                JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = key.attnum
                WHERE c.contype = 'f' AND n.nspname = 'public'""", String.class);
        assertThat(foreignKeys).containsExactlyInAnyOrder(
                "stock_reservations.stock_item_id -> stock_items",
                "stock_movements.stock_item_id -> stock_items",
                "stock_adjustments.stock_item_id -> stock_items",
                "balance_return_lines.balance_return_id -> balance_returns");

        assertThat(jdbc.queryForList("""
                SELECT a.attname FROM pg_index i
                JOIN pg_class c ON c.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey)
                WHERE i.indisprimary AND c.relname = 'processed_events' AND n.nspname = 'public'""", String.class))
                .containsExactlyInAnyOrder("event_id", "consumer");
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'
                AND table_name = 'outbox_events' AND column_name = 'aggregate_id'""", String.class)).isEqualTo("uuid");
    }

    @Test
    void the_data_of_the_previous_schema_is_kept() {
        flyway("2").migrate();
        seedItems();
        UUID dispatch = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID partialLine = UUID.randomUUID();
        UUID fullLine = UUID.randomUUID();

        // Movimientos: una salida con el despacho en el texto y otra con el manifiesto, y una entrada en obra.
        UUID exit = movement(WAREHOUSE_ITEM, "DISPATCH", "30", "Despacho " + dispatch);
        UUID manifestExit = movement(WAREHOUSE_ITEM, "DISPATCH", "5", "Despacho MAN-20261103-7KQ2M9XA");
        UUID entry = movement(WORKSITE_ITEM, "ENTRY", "30", "Recepción " + UUID.randomUUID());
        jdbc.update("INSERT INTO " + t("worksite_supplies") + " VALUES (?, ?, ?, ?, 30, ?)", ORG, WORKSITE, CEMENT,
                WAREHOUSE, AT);

        // Reservas: una parcial y una consumida por completo.
        reservation(partialLine, order, "50", "30", "RESERVED");
        reservation(fullLine, order, "5", "5", "CONSUMED");

        // Conteos: uno ajustado se conserva; uno en curso no cambió nada y no tiene dónde guardarse.
        UUID adjusted = count("ADJUSTED", "Rotura de bolsas");
        countLine(adjusted, "445", "450");
        UUID open = count("IN_PROGRESS", null);
        countLine(open, null, null);

        // Devolución recibida con rechazo.
        UUID returnId = UUID.randomUUID();
        UUID returnLine = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("material_returns") + """
                 (id, organization_id, worksite_id, warehouse_id, status, note, registered_by, registered_at, version)
                 VALUES (?, ?, ?, ?, 'RECEIVED', 'Saldo', ?, ?, 4)""", returnId, ORG, WORKSITE, WAREHOUSE, USER, AT);
        jdbc.update("INSERT INTO " + t("material_return_lines") + """
                 (id, return_id, organization_id, material_id, position, quantity, detail, accepted_qty, rejected_qty,
                  rejection_reason) VALUES (?, ?, ?, ?, 0, 12, 'Detalle', 10, 2, 'Húmedas')""",
                returnLine, returnId, ORG, CEMENT);

        // Asignaciones, outbox y eventos procesados.
        UUID active = assignment(true);
        UUID retired = assignment(false);
        UUID eventId = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("outbox_events") + """
                 (id, event_type, aggregate_type, aggregate_id, organization_id, actor_id, payload, occurred_at)
                 VALUES (?, 'StockReserved', 'ORDER', ?, ?, ?, '{"orderId":"x"}', ?)""", eventId, aggregate.toString(),
                ORG, USER, AT);
        UUID processed = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("processed_events") + " VALUES (?, ?)", processed, AT);

        flyway("3").migrate();

        // Origen: la salida más reciente y la entrada comparten source_ref; la salida con despacho conserva el suyo.
        assertThat(sourceRef(exit)).isEqualTo(dispatch);
        UUID linked = sourceRef(manifestExit);
        assertThat(linked).isNotNull();
        assertThat(sourceRef(entry)).isEqualTo(linked);
        assertThat(jdbc.queryForObject("SELECT created_by FROM " + t("stock_movements") + " WHERE id = ?", UUID.class,
                entry)).isEqualTo(USER);

        assertThat(rows("SELECT status || ' ' || quantity FROM " + t("stock_reservations") + " WHERE order_line_id = ?",
                partialLine)).containsExactlyInAnyOrder("RESERVED 20.000", "CONSUMED 30.000");
        assertThat(rows("SELECT status || ' ' || quantity FROM " + t("stock_reservations") + " WHERE order_line_id = ?",
                fullLine)).containsExactly("CONSUMED 5.000");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t("stock_adjustments"), Long.class)).isEqualTo(1);
        Map<String, Object> adjustment = jdbc.queryForMap("SELECT * FROM " + t("stock_adjustments"));
        assertThat((BigDecimal) adjustment.get("counted_qty")).isEqualByComparingTo("445");
        assertThat((BigDecimal) adjustment.get("system_qty")).isEqualByComparingTo("450");
        assertThat(adjustment).containsEntry("reason", "Rotura de bolsas").containsEntry("stock_item_id", WAREHOUSE_ITEM);

        Map<String, Object> balance = jdbc.queryForMap("SELECT * FROM " + t("balance_returns") + " WHERE id = ?", returnId);
        assertThat(balance).containsEntry("destination_id", WAREHOUSE).containsEntry("status", "RECEIVED")
                .containsEntry("created_by", USER).containsEntry("worksite_id", WORKSITE);
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM " + t("balance_return_lines") + " WHERE id = ?",
                returnLine);
        assertThat((BigDecimal) line.get("declared_qty")).isEqualByComparingTo("12");
        assertThat((BigDecimal) line.get("accepted_qty")).isEqualByComparingTo("10");

        assertThat(new HashSet<>(jdbc.queryForList("SELECT assignment_id FROM " + t("assignment_projection"), UUID.class)))
                .containsExactly(active).doesNotContain(retired);

        Map<String, Object> event = jdbc.queryForMap("SELECT aggregate_id, payload::text AS payload FROM "
                + t("outbox_events") + " WHERE id = ?", eventId);
        assertThat(event).containsEntry("aggregate_id", aggregate);
        assertThat(event.get("payload").toString()).contains("\"aggregateType\": \"ORDER\"")
                .contains("\"actorId\": \"" + USER + "\"").contains("\"orderId\": \"x\"");

        assertThat(rows("SELECT consumer FROM " + t("processed_events") + " WHERE event_id = ?", processed))
                .containsExactlyInAnyOrder("OrderingEventConsumer", "OrganizationEventConsumer");
    }

    private void seedItems() {
        for (Object[] item : List.of(new Object[]{WAREHOUSE_ITEM, WAREHOUSE}, new Object[]{WORKSITE_ITEM, WORKSITE})) {
            jdbc.update("INSERT INTO " + t("stock_items") + """
                     (id, organization_id, location_id, material_id, available_qty, reserved_qty, version, created_at,
                      created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, 450, 0, 0, ?, ?, ?, ?)""",
                    item[0], ORG, item[1], CEMENT, AT, USER, AT, USER);
        }
    }

    private UUID movement(UUID item, String type, String quantity, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("stock_movements") + """
                 (id, organization_id, stock_item_id, type, quantity, balance_after, reference, occurred_at, recorded_by)
                 VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)""", id, ORG, item, type, new BigDecimal(quantity), reference, AT, USER);
        return id;
    }

    private void reservation(UUID line, UUID order, String quantity, String consumed, String status) {
        jdbc.update("INSERT INTO " + t("stock_reservations") + """
                 (id, organization_id, stock_item_id, order_id, order_line_id, quantity, consumed_qty, status,
                  reserved_at, reserved_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), ORG, WAREHOUSE_ITEM, order, line, new BigDecimal(quantity), new BigDecimal(consumed),
                status, AT, USER, AT);
    }

    private UUID count(String status, String reason) {
        UUID id = UUID.randomUUID();
        // El índice único solo admite un conteo abierto por almacén; aquí hay uno abierto y uno cerrado.
        jdbc.update("INSERT INTO " + t("stock_counts") + """
                 (id, organization_id, warehouse_id, status, started_by, started_at, adjustment_reason, version)
                 VALUES (?, ?, ?, ?, ?, ?, ?, 1)""", id, ORG, WAREHOUSE, status, USER, AT, reason);
        return id;
    }

    private void countLine(UUID count, String counted, String system) {
        jdbc.update("INSERT INTO " + t("stock_count_lines") + """
                 (count_id, organization_id, material_id, position, counted_qty, system_qty) VALUES (?, ?, ?, 0, ?, ?)""",
                count, ORG, CEMENT, counted == null ? null : new BigDecimal(counted),
                system == null ? null : new BigDecimal(system));
    }

    private UUID assignment(boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("assignment_projection") + """
                 (assignment_id, organization_id, user_id, location_id, location_type, active, updated_at)
                 VALUES (?, ?, ?, ?, 'WAREHOUSE', ?, ?)""", id, ORG, USER, WAREHOUSE, active, AT);
        return id;
    }

    private UUID sourceRef(UUID movement) {
        return jdbc.queryForObject("SELECT source_ref FROM " + t("stock_movements") + " WHERE id = ?", UUID.class, movement);
    }

    private List<String> rows(String sql, UUID id) {
        return jdbc.queryForList(sql, String.class, id);
    }
}
