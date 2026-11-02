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

/**
 * Devoluciones de saldos ({@code balance_returns} y {@code balance_return_lines}) con SQL directo: cada sentencia
 * filtra por {@code organization_id}. El ERD no tiene versión: la actualización está condicionada por el estado leído,
 * y como cada transición sale de un solo estado, dos operaciones simultáneas no pueden aplicarse ambas. Las líneas solo
 * cambian al verificar lo recibido.
 */
@Repository
class JdbcMaterialReturnRepository implements MaterialReturnRepository {

    private static final String COLUMNS = "id, worksite_id, destination_id, status, created_by, created_at";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    JdbcMaterialReturnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void add(MaterialReturn materialReturn) {
        jdbc.update("""
                INSERT INTO balance_returns (id, organization_id, worksite_id, destination_id, status, created_by,
                                             created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, materialReturn.id(), organizationId(), materialReturn.worksiteId(), materialReturn.warehouseId(),
                materialReturn.status().name(), materialReturn.registeredBy(),
                Timestamp.from(materialReturn.registeredAt()));
        List<Object[]> batch = new ArrayList<>();
        for (MaterialReturnLine line : materialReturn.lines()) {
            batch.add(new Object[]{line.id(), organizationId(), materialReturn.id(), line.materialId(),
                    line.quantity()});
        }
        jdbc.batchUpdate("""
                INSERT INTO balance_return_lines (id, organization_id, balance_return_id, material_id, declared_qty)
                VALUES (?, ?, ?, ?, ?)
                """, batch);
    }

    @Override
    public boolean update(MaterialReturn materialReturn) {
        int updated = jdbc.update("""
                UPDATE balance_returns SET status = ?
                WHERE id = ? AND organization_id = ? AND status = ?
                """, materialReturn.status().name(), materialReturn.id(), organizationId(),
                materialReturn.persistedStatus().name());
        if (updated == 0) {
            return false;
        }
        List<Object[]> batch = materialReturn.lines().stream()
                .map(line -> new Object[]{line.acceptedQty(), line.id(), organizationId()})
                .toList();
        jdbc.batchUpdate("UPDATE balance_return_lines SET accepted_qty = ? WHERE id = ? AND organization_id = ?", batch);
        return true;
    }

    @Override
    public Optional<MaterialReturn> find(UUID returnId) {
        return load("SELECT " + COLUMNS + " FROM balance_returns WHERE organization_id = :organizationId AND id = :id",
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
            scopes.add("destination_id IN (:warehouses)");
            params.addValue("warehouses", warehouseIds);
        }
        String sql = "SELECT " + COLUMNS + " FROM balance_returns WHERE organization_id = :organizationId AND ("
                + String.join(" OR ", scopes) + ")";
        if (status != null) {
            sql += " AND status = :status";
            params.addValue("status", status.name());
        }
        return load(sql + " ORDER BY created_at DESC, id DESC", params);
    }

    private List<MaterialReturn> load(String sql, MapSqlParameterSource params) {
        List<Row> rows = named.query(sql, params, (rs, rowNum) -> row(rs));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<MaterialReturnLine>> lines = new LinkedHashMap<>();
        named.query("""
                        SELECT id, balance_return_id, material_id, declared_qty, accepted_qty
                        FROM balance_return_lines
                        WHERE organization_id = :organizationId AND balance_return_id IN (:ids)
                        ORDER BY balance_return_id, id
                        """,
                new MapSqlParameterSource("organizationId", organizationId())
                        .addValue("ids", rows.stream().map(Row::id).toList()),
                (rs, rowNum) -> lines.computeIfAbsent(rs.getObject("balance_return_id", UUID.class),
                                ignored -> new ArrayList<>())
                        .add(new MaterialReturnLine(rs.getObject("id", UUID.class),
                                rs.getObject("material_id", UUID.class), rs.getBigDecimal("declared_qty"),
                                rs.getBigDecimal("accepted_qty"), null)));
        return rows.stream().map(row -> row.toDomain(lines.getOrDefault(row.id(), List.of()))).toList();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("worksite_id", UUID.class),
                rs.getObject("destination_id", UUID.class), MaterialReturn.Status.valueOf(rs.getString("status")),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant());
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }

    private record Row(UUID id, UUID worksiteId, UUID warehouseId, MaterialReturn.Status status, UUID registeredBy,
            Instant registeredAt) {

        MaterialReturn toDomain(List<MaterialReturnLine> lines) {
            return new MaterialReturn(id, worksiteId, warehouseId, status, registeredBy, registeredAt, lines);
        }
    }
}
