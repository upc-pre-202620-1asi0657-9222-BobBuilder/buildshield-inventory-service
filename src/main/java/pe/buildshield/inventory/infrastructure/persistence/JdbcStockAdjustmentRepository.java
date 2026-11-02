package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.inventory.domain.model.StockAdjustment;
import pe.buildshield.inventory.domain.model.StockAdjustmentRepository;
import pe.buildshield.inventory.shared.tenant.TenantContext;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ajustes por conteo físico con SQL directo, como el resto del stock: cada sentencia filtra por organización. */
@Repository
class JdbcStockAdjustmentRepository implements StockAdjustmentRepository {

    private static final String SELECT = """
            SELECT a.id, a.stock_item_id, i.location_id, i.material_id, a.counted_qty, a.system_qty, a.reason,
                   a.created_by, a.created_at
            FROM stock_adjustments a JOIN stock_items i ON i.id = a.stock_item_id
            """;

    private static final RowMapper<StockAdjustment> ADJUSTMENT = (rs, rowNum) -> new StockAdjustment(
            rs.getObject("id", UUID.class),
            rs.getObject("stock_item_id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("material_id", UUID.class),
            rs.getBigDecimal("counted_qty"),
            rs.getBigDecimal("system_qty"),
            rs.getString("reason"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    JdbcStockAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void add(StockAdjustment adjustment) {
        jdbc.update("""
                INSERT INTO stock_adjustments (id, organization_id, stock_item_id, counted_qty, system_qty, reason,
                                               created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, adjustment.id(), organizationId(), adjustment.stockItemId(), adjustment.countedQty(),
                adjustment.systemQty(), adjustment.reason(), adjustment.createdBy(),
                Timestamp.from(adjustment.createdAt()));
    }

    @Override
    public Optional<StockAdjustment> find(UUID adjustmentId) {
        return jdbc.query(SELECT + " WHERE a.organization_id = ? AND a.id = ?", ADJUSTMENT, organizationId(),
                adjustmentId).stream().findFirst();
    }

    @Override
    public List<StockAdjustment> findByLocations(Collection<UUID> locationIds, UUID materialId) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", organizationId())
                .addValue("locations", locationIds).addValue("materialId", materialId);
        return named.query(SELECT + " WHERE a.organization_id = :organizationId AND i.location_id IN (:locations)"
                + (materialId == null ? "" : " AND i.material_id = :materialId")
                + " ORDER BY a.created_at DESC, a.id DESC", params, ADJUSTMENT);
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }
}
