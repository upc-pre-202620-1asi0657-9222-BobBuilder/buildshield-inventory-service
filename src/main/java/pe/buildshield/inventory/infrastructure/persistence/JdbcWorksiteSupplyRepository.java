package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.inventory.domain.model.WorksiteSupplyRepository;
import pe.buildshield.inventory.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tabla {@code worksite_supplies}: cuánto recibió cada obra de cada material desde cada almacén. La alimentan las
 * recepciones (consumidor de ordering) y la lee la devolución de saldos. Filtra por {@code organization_id}.
 */
@Repository
class JdbcWorksiteSupplyRepository implements WorksiteSupplyRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    JdbcWorksiteSupplyRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    @Override
    public void recordSupply(UUID worksiteId, UUID materialId, UUID warehouseId, BigDecimal quantity) {
        jdbc.update("""
                INSERT INTO worksite_supplies (organization_id, worksite_id, material_id, warehouse_id, received_qty,
                                               last_received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, worksite_id, material_id, warehouse_id) DO UPDATE SET
                    received_qty = worksite_supplies.received_qty + EXCLUDED.received_qty,
                    last_received_at = EXCLUDED.last_received_at
                """, TenantContext.require().organizationId(), worksiteId, materialId, warehouseId, quantity,
                Timestamp.from(clock.instant()));
    }

    @Override
    public Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds) {
        Map<UUID, Set<UUID>> origins = new HashMap<>();
        if (materialIds.isEmpty()) {
            return origins;
        }
        named.query("""
                        SELECT material_id, warehouse_id FROM worksite_supplies
                        WHERE organization_id = :organizationId AND worksite_id = :worksiteId
                          AND material_id IN (:materials)
                        """,
                new MapSqlParameterSource("organizationId", TenantContext.require().organizationId())
                        .addValue("worksiteId", worksiteId).addValue("materials", materialIds),
                (RowCallbackHandler) rs -> origins
                        .computeIfAbsent(rs.getObject("material_id", UUID.class), ignored -> new HashSet<>())
                        .add(rs.getObject("warehouse_id", UUID.class)));
        return origins;
    }
}
