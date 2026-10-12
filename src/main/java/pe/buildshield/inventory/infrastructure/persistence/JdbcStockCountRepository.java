package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.domain.model.StockCountLine;
import pe.buildshield.inventory.domain.model.StockCountRepository;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.tenant.TenantContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conteos de validación con SQL directo, como el resto del stock: cada sentencia filtra por {@code organization_id}
 * y la actualización está condicionada por la versión leída (bloqueo optimista). Las líneas se reescriben con el
 * conteo.
 */
@Repository
class JdbcStockCountRepository implements StockCountRepository {

    private static final String COLUMNS = """
            id, warehouse_id, status, note, started_by, started_at, finished_by, finished_at, review_note, reviewed_by,
            reviewed_at, adjustment_reason, adjusted_by, adjusted_at, cancelled_by, cancelled_at, version
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    JdbcStockCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void add(StockCount count) {
        try {
            jdbc.update("""
                    INSERT INTO stock_counts (id, organization_id, warehouse_id, status, note, started_by, started_at,
                                              version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """, count.id(), organizationId(), count.warehouseId(), count.status().name(), count.note(),
                    count.startedBy(), Timestamp.from(count.startedAt()));
        } catch (DuplicateKeyException ex) {
            // Última defensa: el índice único de conteos abiertos por almacén (dos inicios simultáneos).
            throw new ConflictException("COUNT_ALREADY_OPEN", "El almacén ya tiene un conteo abierto");
        }
        insertLines(count);
    }

    @Override
    public boolean update(StockCount count) {
        int updated = jdbc.update("""
                UPDATE stock_counts SET status = ?, finished_by = ?, finished_at = ?, review_note = ?, reviewed_by = ?,
                    reviewed_at = ?, adjustment_reason = ?, adjusted_by = ?, adjusted_at = ?, cancelled_by = ?,
                    cancelled_at = ?, version = version + 1
                WHERE id = ? AND organization_id = ? AND version = ?
                """, count.status().name(), count.finishedBy(), timestamp(count.finishedAt()), count.reviewNote(),
                count.reviewedBy(), timestamp(count.reviewedAt()), count.adjustmentReason(), count.adjustedBy(),
                timestamp(count.adjustedAt()), count.cancelledBy(), timestamp(count.cancelledAt()), count.id(),
                organizationId(), count.version());
        if (updated == 0) {
            return false;
        }
        jdbc.update("DELETE FROM stock_count_lines WHERE count_id = ? AND organization_id = ?", count.id(),
                organizationId());
        insertLines(count);
        return true;
    }

    @Override
    public Optional<StockCount> find(UUID countId) {
        List<StockCount> found = load("SELECT " + COLUMNS + " FROM stock_counts WHERE organization_id = :organizationId"
                + " AND id = :id", new MapSqlParameterSource("organizationId", organizationId()).addValue("id", countId));
        return found.stream().findFirst();
    }

    @Override
    public boolean hasOpenCount(UUID warehouseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM stock_counts WHERE organization_id = ? AND warehouse_id = ?
                               AND status IN ('IN_PROGRESS', 'DIFFERENCES_FOUND', 'REVIEWED'))""",
                Boolean.class, organizationId(), warehouseId));
    }

    @Override
    public List<StockCount> findByWarehouses(Collection<UUID> warehouseIds, StockCount.Status status) {
        if (warehouseIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", organizationId())
                .addValue("warehouses", warehouseIds);
        String sql = "SELECT " + COLUMNS + " FROM stock_counts WHERE organization_id = :organizationId"
                + " AND warehouse_id IN (:warehouses)";
        if (status != null) {
            sql += " AND status = :status";
            params.addValue("status", status.name());
        }
        return load(sql + " ORDER BY started_at DESC, id", params);
    }

    private List<StockCount> load(String sql, MapSqlParameterSource params) {
        List<Row> rows = named.query(sql, params, (rs, rowNum) -> row(rs));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<StockCountLine>> lines = new LinkedHashMap<>();
        named.query("""
                        SELECT count_id, material_id, counted_qty, system_qty FROM stock_count_lines
                        WHERE organization_id = :organizationId AND count_id IN (:ids) ORDER BY count_id, position
                        """,
                new MapSqlParameterSource("organizationId", organizationId())
                        .addValue("ids", rows.stream().map(Row::id).toList()),
                (rs, rowNum) -> lines.computeIfAbsent(rs.getObject("count_id", UUID.class), ignored -> new ArrayList<>())
                        .add(new StockCountLine(rs.getObject("material_id", UUID.class), rs.getBigDecimal("counted_qty"),
                                rs.getBigDecimal("system_qty"))));
        return rows.stream().map(row -> row.toDomain(lines.getOrDefault(row.id(), List.of()))).toList();
    }

    private void insertLines(StockCount count) {
        List<StockCountLine> lines = count.lines();
        List<Object[]> batch = new ArrayList<>();
        for (int position = 0; position < lines.size(); position++) {
            StockCountLine line = lines.get(position);
            batch.add(new Object[]{count.id(), organizationId(), line.materialId(), position, line.countedQty(),
                    line.systemQty(), line.difference()});
        }
        jdbc.batchUpdate("""
                INSERT INTO stock_count_lines (count_id, organization_id, material_id, position, counted_qty, system_qty,
                                               difference)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                StockCount.Status.valueOf(rs.getString("status")), rs.getString("note"),
                rs.getObject("started_by", UUID.class), instant(rs, "started_at"),
                rs.getObject("finished_by", UUID.class), instant(rs, "finished_at"), rs.getString("review_note"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"), rs.getString("adjustment_reason"),
                rs.getObject("adjusted_by", UUID.class), instant(rs, "adjusted_at"),
                rs.getObject("cancelled_by", UUID.class), instant(rs, "cancelled_at"), rs.getLong("version"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }

    private record Row(UUID id, UUID warehouseId, StockCount.Status status, String note, UUID startedBy,
            Instant startedAt, UUID finishedBy, Instant finishedAt, String reviewNote, UUID reviewedBy,
            Instant reviewedAt, String adjustmentReason, UUID adjustedBy, Instant adjustedAt, UUID cancelledBy,
            Instant cancelledAt, long version) {

        StockCount toDomain(List<StockCountLine> lines) {
            StockCount count = new StockCount(id, warehouseId, status, note, startedBy, startedAt, lines, version);
            count.restore(finishedBy, finishedAt, reviewNote, reviewedBy, reviewedAt, adjustmentReason, adjustedBy,
                    adjustedAt, cancelledBy, cancelledAt);
            return count;
        }
    }
}
