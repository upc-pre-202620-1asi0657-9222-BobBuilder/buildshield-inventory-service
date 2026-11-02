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
 * Comprueba que ningún dato se pierde y que el esquema final (el de la aplicación) es el del ERD ampliado.
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
    /** Quién solicitó, decidió, despachó, recibió y anuló la devolución. */
    private static final UUID[] STEP = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID()};

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
                        "balance_after", "reference", "source_ref", "created_by", "created_at")),
                Map.entry("stock_counts", Set.of("id", "organization_id", "warehouse_id", "status", "note", "started_by",
                        "started_at", "finished_by", "finished_at", "review_note", "reviewed_by", "reviewed_at",
                        "adjustment_reason", "adjusted_by", "adjusted_at", "cancelled_by", "cancelled_at", "version")),
                Map.entry("stock_count_lines", Set.of("count_id", "organization_id", "material_id", "position",
                        "counted_qty", "system_qty", "difference")),
                Map.entry("balance_returns", Set.of("id", "organization_id", "worksite_id", "destination_id", "status",
                        "return_dispatch_id", "note", "created_by", "created_at", "requested_by", "requested_at",
                        "decided_by", "decided_at", "rejection_reason", "dispatched_by", "dispatched_at", "received_by",
                        "received_at", "cancelled_by", "cancelled_at", "version")),
                Map.entry("balance_return_lines", Set.of("id", "organization_id", "balance_return_id", "material_id",
                        "position", "declared_qty", "detail", "accepted_qty", "rejection_reason")),
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
                "stock_count_lines.count_id -> stock_counts",
                "balance_return_lines.balance_return_id -> balance_returns");

        // Un conteo abierto por almacén y un material por devolución siguen siendo reglas de la base.
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN (
                    'uk_stock_counts_open_per_warehouse', 'uk_balance_return_lines_material',
                    'uk_stock_counts_org_id')""", String.class))
                .containsExactlyInAnyOrder("uk_stock_counts_open_per_warehouse", "uk_balance_return_lines_material");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_stock_movements_quantity'""",
                String.class)).contains("balance_after >= ");

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

        // Conteos: se conservan todos, abiertos o cerrados, con su avance y sus notas.
        UUID adjusted = count("ADJUSTED", "Rotura de bolsas");
        countLine(adjusted, "445", "450");
        UUID open = count("IN_PROGRESS", null);
        countLine(open, null, null);

        // Devolución recibida con rechazo, con cada paso firmado.
        UUID returnId = UUID.randomUUID();
        UUID returnLine = UUID.randomUUID();
        jdbc.update("INSERT INTO " + t("material_returns") + """
                 (id, organization_id, worksite_id, warehouse_id, status, note, registered_by, registered_at,
                  requested_by, requested_at, decided_by, decided_at, rejection_reason, dispatched_by, dispatched_at,
                  received_by, received_at, cancelled_by, cancelled_at, version)
                 VALUES (?, ?, ?, ?, 'RECEIVED', 'Saldo', ?, ?, ?, ?, ?, ?, 'Motivo', ?, ?, ?, ?, ?, ?, 4)""",
                returnId, ORG, WORKSITE, WAREHOUSE, USER, AT, STEP[0], AT, STEP[1], AT, STEP[2], AT, STEP[3], AT,
                STEP[4], AT);
        jdbc.update("INSERT INTO " + t("material_return_lines") + """
                 (id, return_id, organization_id, material_id, position, quantity, detail, accepted_qty, rejected_qty,
                  rejection_reason) VALUES (?, ?, ?, ?, 3, 12, 'Detalle', 10, 2, 'Húmedas')""",
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

        // El texto y el saldo guardado de cada movimiento sobreviven.
        Map<String, Object> kept = jdbc.queryForMap("SELECT reference, balance_after FROM " + t("stock_movements")
                + " WHERE id = ?", manifestExit);
        assertThat(kept).containsEntry("reference", "Despacho MAN-20261103-7KQ2M9XA");
        assertThat((BigDecimal) kept.get("balance_after")).isEqualByComparingTo("120.5");

        assertThat(rows("SELECT status || ' ' || quantity FROM " + t("stock_reservations") + " WHERE order_line_id = ?",
                partialLine)).containsExactlyInAnyOrder("RESERVED 20.000", "CONSUMED 30.000");
        assertThat(rows("SELECT status || ' ' || quantity FROM " + t("stock_reservations") + " WHERE order_line_id = ?",
                fullLine)).containsExactly("CONSUMED 5.000");

        Map<String, Object> closedCount = jdbc.queryForMap("SELECT * FROM " + t("stock_counts") + " WHERE id = ?", adjusted);
        assertThat(closedCount).containsEntry("status", "ADJUSTED").containsEntry("note", "Mensual")
                .containsEntry("adjustment_reason", "Rotura de bolsas").containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("SELECT status FROM " + t("stock_counts") + " WHERE id = ?", open))
                .containsEntry("status", "IN_PROGRESS");
        Map<String, Object> countedLine = jdbc.queryForMap("SELECT * FROM " + t("stock_count_lines") + " WHERE count_id = ?",
                adjusted);
        assertThat((BigDecimal) countedLine.get("counted_qty")).isEqualByComparingTo("445");
        assertThat((BigDecimal) countedLine.get("system_qty")).isEqualByComparingTo("450");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t("stock_count_lines") + " WHERE count_id = ?", Long.class,
                open)).isEqualTo(1);

        Map<String, Object> balance = jdbc.queryForMap("SELECT * FROM " + t("balance_returns") + " WHERE id = ?", returnId);
        assertThat(balance).containsEntry("destination_id", WAREHOUSE).containsEntry("status", "RECEIVED")
                .containsEntry("created_by", USER).containsEntry("worksite_id", WORKSITE)
                .containsEntry("note", "Saldo").containsEntry("rejection_reason", "Motivo").containsEntry("version", 4L)
                .containsEntry("requested_by", STEP[0]).containsEntry("decided_by", STEP[1])
                .containsEntry("dispatched_by", STEP[2]).containsEntry("received_by", STEP[3])
                .containsEntry("cancelled_by", STEP[4]);
        for (String step : List.of("requested_at", "decided_at", "dispatched_at", "received_at", "cancelled_at")) {
            assertThat(balance.get(step)).as(step).isNotNull();
        }
        assertThat(balance.get("return_dispatch_id")).isNull();
        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM " + t("balance_return_lines") + " WHERE id = ?",
                returnLine);
        assertThat((BigDecimal) line.get("declared_qty")).isEqualByComparingTo("12");
        assertThat((BigDecimal) line.get("accepted_qty")).isEqualByComparingTo("10");
        assertThat(line).containsEntry("detail", "Detalle").containsEntry("rejection_reason", "Húmedas")
                .containsEntry("position", 3);

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
                 VALUES (?, ?, ?, ?, ?, 120.5, ?, ?, ?)""", id, ORG, item, type, new BigDecimal(quantity), reference, AT, USER);
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
                 (id, organization_id, warehouse_id, status, note, started_by, started_at, adjustment_reason, version)
                 VALUES (?, ?, ?, ?, 'Mensual', ?, ?, ?, 1)""", id, ORG, WAREHOUSE, status, USER, AT, reason);
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
