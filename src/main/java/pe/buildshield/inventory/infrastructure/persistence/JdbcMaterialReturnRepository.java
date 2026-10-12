package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.domain.model.MaterialReturnLine;
import pe.buildshield.inventory.domain.model.MaterialReturnRepository;
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

import static pe.buildshield.inventory.infrastructure.persistence.JdbcStockCountRepository.instant;
import static pe.buildshield.inventory.infrastructure.persistence.JdbcStockCountRepository.timestamp;

/**
 * Devoluciones de saldos con SQL directo: cada sentencia filtra por {@code organization_id} y la actualización está
 * condicionada por la versión leída (bloqueo optimista). Las líneas solo cambian al verificar lo recibido.
 */
@Repository
class JdbcMaterialReturnRepository implements MaterialReturnRepository {

    private static final String COLUMNS = """
            id, worksite_id, warehouse_id, status, note, registered_by, registered_at, requested_by, requested_at,
            decided_by, decided_at, rejection_reason, dispatched_by, dispatched_at, received_by, received_at,
            cancelled_by, cancelled_at, version
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    JdbcMaterialReturnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void add(MaterialReturn materialReturn) {
        jdbc.update("""
                INSERT INTO material_returns (id, organization_id, worksite_id, warehouse_id, status, note,
                                              registered_by, registered_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, materialReturn.id(), organizationId(), materialReturn.worksiteId(), materialReturn.warehouseId(),
                materialReturn.status().name(), materialReturn.note(), materialReturn.registeredBy(),
                Timestamp.from(materialReturn.registeredAt()));
        List<MaterialReturnLine> lines = materialReturn.lines();
        List<Object[]> batch = new ArrayList<>();
        for (int position = 0; position < lines.size(); position++) {
            MaterialReturnLine line = lines.get(position);
            batch.add(new Object[]{line.id(), materialReturn.id(), organizationId(), line.materialId(), position,
                    line.quantity(), line.detail()});
        }
        jdbc.batchUpdate("""
                INSERT INTO material_return_lines (id, return_id, organization_id, material_id, position, quantity,
                                                   detail)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    @Override
    public boolean update(MaterialReturn materialReturn) {
        int updated = jdbc.update("""
                UPDATE material_returns SET status = ?, requested_by = ?, requested_at = ?, decided_by = ?,
                    decided_at = ?, rejection_reason = ?, dispatched_by = ?, dispatched_at = ?, received_by = ?,
                    received_at = ?, cancelled_by = ?, cancelled_at = ?, version = version + 1
                WHERE id = ? AND organization_id = ? AND version = ?
                """, materialReturn.status().name(), materialReturn.requestedBy(),
                timestamp(materialReturn.requestedAt()), materialReturn.decidedBy(),
                timestamp(materialReturn.decidedAt()), materialReturn.rejectionReason(),
                materialReturn.dispatchedBy(), timestamp(materialReturn.dispatchedAt()), materialReturn.receivedBy(),
                timestamp(materialReturn.receivedAt()), materialReturn.cancelledBy(),
                timestamp(materialReturn.cancelledAt()), materialReturn.id(), organizationId(),
                materialReturn.version());
        if (updated == 0) {
            return false;
        }
        List<Object[]> batch = materialReturn.lines().stream()
                .map(line -> new Object[]{line.acceptedQty(), line.rejectedQty(), line.rejectionReason(), line.id(),
                        organizationId()})
                .toList();
        jdbc.batchUpdate("""
                UPDATE material_return_lines SET accepted_qty = ?, rejected_qty = ?, rejection_reason = ?
                WHERE id = ? AND organization_id = ?
                """, batch);
        return true;
    }

    @Override
    public Optional<MaterialReturn> find(UUID returnId) {
        return load("SELECT " + COLUMNS + " FROM material_returns WHERE organization_id = :organizationId AND id = :id",
                new MapSqlParameterSource("organizationId", organizationId()).addValue("id", returnId))
                .stream().findFirst();
    }

    @Override
    public List<MaterialReturn> findByLocations(Collection<UUID> worksiteIds, Collection<UUID> warehouseIds,
            MaterialReturn.Status status) {
        if (worksiteIds.isEmpty() && warehouseIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", organizationId());
        List<String> scopes = new ArrayList<>();
        if (!worksiteIds.isEmpty()) {
            scopes.add("worksite_id IN (:worksites)");
            params.addValue("worksites", worksiteIds);
        }
        if (!warehouseIds.isEmpty()) {
            scopes.add("warehouse_id IN (:warehouses)");
            params.addValue("warehouses", warehouseIds);
        }
        String sql = "SELECT " + COLUMNS + " FROM material_returns WHERE organization_id = :organizationId AND ("
                + String.join(" OR ", scopes) + ")";
        if (status != null) {
            sql += " AND status = :status";
            params.addValue("status", status.name());
        }
        return load(sql + " ORDER BY registered_at DESC, id", params);
    }

    private List<MaterialReturn> load(String sql, MapSqlParameterSource params) {
        List<Row> rows = named.query(sql, params, (rs, rowNum) -> row(rs));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<MaterialReturnLine>> lines = new LinkedHashMap<>();
        named.query("""
                        SELECT id, return_id, material_id, quantity, detail, accepted_qty, rejected_qty, rejection_reason
                        FROM material_return_lines
                        WHERE organization_id = :organizationId AND return_id IN (:ids) ORDER BY return_id, position
                        """,
                new MapSqlParameterSource("organizationId", organizationId())
                        .addValue("ids", rows.stream().map(Row::id).toList()),
                (rs, rowNum) -> lines.computeIfAbsent(rs.getObject("return_id", UUID.class), ignored -> new ArrayList<>())
                        .add(new MaterialReturnLine(rs.getObject("id", UUID.class),
                                rs.getObject("material_id", UUID.class), rs.getBigDecimal("quantity"),
                                rs.getString("detail"), rs.getBigDecimal("accepted_qty"),
                                rs.getBigDecimal("rejected_qty"), rs.getString("rejection_reason"))));
        return rows.stream().map(row -> row.toDomain(lines.getOrDefault(row.id(), List.of()))).toList();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("worksite_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class), MaterialReturn.Status.valueOf(rs.getString("status")),
                rs.getString("note"), rs.getObject("registered_by", UUID.class), instant(rs, "registered_at"),
                rs.getObject("requested_by", UUID.class), instant(rs, "requested_at"),
                rs.getObject("decided_by", UUID.class), instant(rs, "decided_at"), rs.getString("rejection_reason"),
                rs.getObject("dispatched_by", UUID.class), instant(rs, "dispatched_at"),
                rs.getObject("received_by", UUID.class), instant(rs, "received_at"),
                rs.getObject("cancelled_by", UUID.class), instant(rs, "cancelled_at"), rs.getLong("version"));
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }

    private record Row(UUID id, UUID worksiteId, UUID warehouseId, MaterialReturn.Status status, String note,
            UUID registeredBy, Instant registeredAt, UUID requestedBy, Instant requestedAt, UUID decidedBy,
            Instant decidedAt, String rejectionReason, UUID dispatchedBy, Instant dispatchedAt, UUID receivedBy,
            Instant receivedAt, UUID cancelledBy, Instant cancelledAt, long version) {

        MaterialReturn toDomain(List<MaterialReturnLine> lines) {
            MaterialReturn materialReturn = new MaterialReturn(id, worksiteId, warehouseId, status, note, registeredBy,
                    registeredAt, lines, version);
            materialReturn.restore(requestedBy, requestedAt, decidedBy, decidedAt, rejectionReason, dispatchedBy,
                    dispatchedAt, receivedBy, receivedAt, cancelledBy, cancelledAt);
            return materialReturn;
        }
    }
}
