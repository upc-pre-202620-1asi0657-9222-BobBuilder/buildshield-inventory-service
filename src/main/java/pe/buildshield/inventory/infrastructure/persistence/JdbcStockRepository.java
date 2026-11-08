package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import pe.buildshield.inventory.domain.model.MovementFilter;
import pe.buildshield.inventory.domain.model.MovementPage;
import pe.buildshield.inventory.domain.model.Quantity;
import pe.buildshield.inventory.domain.model.RecordedMovement;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.domain.model.StockMovement;
import pe.buildshield.inventory.domain.model.StockRepository;
import pe.buildshield.inventory.domain.model.StockReservation;
import pe.buildshield.inventory.domain.model.TimeOrderedId;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stock con SQL directo (portado del Core): reservar, consumir y liberar necesitan leer siempre lo último
 * confirmado (sin la caché de JPA) y un UPDATE condicionado por versión y cantidad. Como JDBC no pasa por el
 * filtro multiempresa de Hibernate, cada sentencia filtra por {@code organization_id} de forma explícita.
 */
@Repository
class JdbcStockRepository implements StockRepository {

    private static final RowMapper<StockItem> ITEM = (rs, rowNum) -> new StockItem(
            rs.getObject("id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("material_id", UUID.class),
            rs.getBigDecimal("available_qty"),
            rs.getBigDecimal("reserved_qty"),
            rs.getLong("version"));

    private static final String RESERVATION_COLUMNS = """
            r.id, r.stock_item_id, i.location_id, i.material_id, r.order_id, r.order_line_id, r.quantity, r.status,
            r.created_at
            FROM stock_reservations r JOIN stock_items i ON i.id = r.stock_item_id
            """;

    private static final RowMapper<StockReservation> RESERVATION = (rs, rowNum) -> new StockReservation(
            rs.getObject("id", UUID.class),
            rs.getObject("stock_item_id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("material_id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getObject("order_line_id", UUID.class),
            rs.getBigDecimal("quantity"),
            StockReservation.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RecordedMovement> MOVEMENT = (rs, rowNum) -> new RecordedMovement(
            rs.getObject("id", UUID.class),
            rs.getObject("location_id", UUID.class),
            rs.getObject("material_id", UUID.class),
            StockMovement.Type.valueOf(rs.getString("type")),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("balance_after"),
            rs.getString("reference"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("created_by", UUID.class));

    private static final String MOVEMENTS = """
            SELECT m.id, i.location_id, i.material_id, m.type, m.quantity, m.balance_after, m.reference, m.created_at,
                   m.created_by
            FROM stock_movements m JOIN stock_items i ON i.id = m.stock_item_id
            WHERE m.organization_id = :organizationId AND i.location_id IN (:locations)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final StockItemRepository jpa;
    private final Clock clock;

    JdbcStockRepository(JdbcTemplate jdbc, StockItemRepository jpa, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.jpa = jpa;
        this.clock = clock;
    }

    @Override
    public Optional<StockItem> find(UUID locationId, UUID materialId) {
        return jdbc.query("""
                        SELECT id, location_id, material_id, available_qty, reserved_qty, version
                        FROM stock_items
                        WHERE organization_id = ? AND location_id = ? AND material_id = ?
                        """, ITEM, organizationId(), locationId, materialId)
                .stream().findFirst();
    }

    @Override
    public List<StockItem> findByLocations(Collection<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByLocationIdInOrderByLocationIdAscMaterialIdAsc(locationIds).stream()
                .map(StockItemJpaEntity::toDomain).toList();
    }

    @Override
    public boolean tryDeduct(UUID stockItemId, long expectedVersion, Quantity quantity) {
        TenantInfo tenant = TenantContext.require();
        return jdbc.update("""
                        UPDATE stock_items
                        SET available_qty = available_qty - ?, version = version + 1, updated_at = ?, updated_by = ?
                        WHERE id = ? AND organization_id = ? AND version = ? AND available_qty >= ?
                        """,
                quantity.value(), Timestamp.from(clock.instant()), tenant.userId(),
                stockItemId, tenant.organizationId(), expectedVersion, quantity.value()) == 1;
    }

    @Override
    public boolean tryReserve(UUID stockItemId, long expectedVersion, Quantity quantity) {
        TenantInfo tenant = TenantContext.require();
        return jdbc.update("""
                        UPDATE stock_items
                        SET available_qty = available_qty - ?, reserved_qty = reserved_qty + ?, version = version + 1,
                            updated_at = ?, updated_by = ?
                        WHERE id = ? AND organization_id = ? AND version = ? AND available_qty >= ?
                        """,
                quantity.value(), quantity.value(), Timestamp.from(clock.instant()), tenant.userId(),
                stockItemId, tenant.organizationId(), expectedVersion, quantity.value()) == 1;
    }

    @Override
    public boolean tryConsumeReserved(UUID stockItemId, long expectedVersion, Quantity quantity) {
        TenantInfo tenant = TenantContext.require();
        return jdbc.update("""
                        UPDATE stock_items
                        SET reserved_qty = reserved_qty - ?, version = version + 1, updated_at = ?, updated_by = ?
                        WHERE id = ? AND organization_id = ? AND version = ? AND reserved_qty >= ?
                        """,
                quantity.value(), Timestamp.from(clock.instant()), tenant.userId(),
                stockItemId, tenant.organizationId(), expectedVersion, quantity.value()) == 1;
    }

    @Override
    public boolean tryRelease(UUID stockItemId, long expectedVersion, Quantity quantity) {
        TenantInfo tenant = TenantContext.require();
        return jdbc.update("""
                        UPDATE stock_items
                        SET reserved_qty = reserved_qty - ?, available_qty = available_qty + ?, version = version + 1,
                            updated_at = ?, updated_by = ?
                        WHERE id = ? AND organization_id = ? AND version = ? AND reserved_qty >= ?
                        """,
                quantity.value(), quantity.value(), Timestamp.from(clock.instant()), tenant.userId(),
                stockItemId, tenant.organizationId(), expectedVersion, quantity.value()) == 1;
    }

    @Override
    public StockItem add(UUID locationId, UUID materialId, Quantity quantity) {
        TenantInfo tenant = TenantContext.require();
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.queryForObject("""
                        INSERT INTO stock_items (id, organization_id, location_id, material_id, available_qty,
                                                 reserved_qty, version, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
                        ON CONFLICT (organization_id, location_id, material_id) DO UPDATE SET
                            available_qty = stock_items.available_qty + EXCLUDED.available_qty,
                            version = stock_items.version + 1,
                            updated_at = EXCLUDED.updated_at,
                            updated_by = EXCLUDED.updated_by
                        RETURNING id, location_id, material_id, available_qty, reserved_qty, version
                        """, ITEM,
                UUID.randomUUID(), tenant.organizationId(), locationId, materialId, quantity.value(),
                now, tenant.userId(), now, tenant.userId());
    }

    @Override
    public void record(StockMovement movement) {
        TenantInfo tenant = TenantContext.require();
        jdbc.update("""
                        INSERT INTO stock_movements (id, organization_id, stock_item_id, type, quantity, balance_after,
                                                     reference, source_ref, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                TimeOrderedId.next(), tenant.organizationId(), movement.stockItemId(), movement.type().name(),
                movement.quantity(), movement.balanceAfter(), movement.reference(), movement.sourceRef(), tenant.userId(),
                Timestamp.from(movement.createdAt()));
    }

    @Override
    public List<RecordedMovement> findMovements(Collection<UUID> locationIds, UUID materialId, int limit) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", organizationId())
                .addValue("locations", locationIds)
                .addValue("materialId", materialId)
                .addValue("limit", limit);
        return named.query(MOVEMENTS + (materialId == null ? "" : " AND i.material_id = :materialId ") + """
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT :limit
                """, params, MOVEMENT);
    }

    @Override
    public MovementPage findMovementPage(Collection<UUID> locationIds, MovementFilter filter, int page, int size) {
        if (locationIds.isEmpty()) {
            return new MovementPage(List.of(), page, size, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", organizationId())
                .addValue("locations", locationIds)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        StringBuilder where = new StringBuilder();
        if (filter.materialId() != null) {
            where.append(" AND i.material_id = :materialId");
            params.addValue("materialId", filter.materialId());
        }
        if (filter.type() != null) {
            where.append(" AND m.type = :type");
            params.addValue("type", filter.type().name());
        }
        if (filter.from() != null) {
            where.append(" AND m.created_at >= :from");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND m.created_at < :to");
            params.addValue("to", Timestamp.from(filter.to()));
        }
        Long total = named.queryForObject("SELECT count(*) FROM (" + MOVEMENTS + where + ") counted", params,
                Long.class);
        List<RecordedMovement> items = named.query(MOVEMENTS + where + """

                ORDER BY m.created_at DESC, m.id DESC
                LIMIT :limit OFFSET :offset
                """, params, MOVEMENT);
        return new MovementPage(items, page, size, total == null ? 0 : total);
    }

    @Override
    public Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds) {
        Map<UUID, Set<UUID>> origins = new HashMap<>();
        if (materialIds.isEmpty()) {
            return origins;
        }
        named.query("""
                        SELECT DISTINCT received.material_id, origin.location_id
                        FROM stock_movements entry
                        JOIN stock_items received ON received.id = entry.stock_item_id
                        JOIN stock_movements departure ON departure.organization_id = entry.organization_id
                             AND departure.source_ref = entry.source_ref AND departure.type IN ('DISPATCH', 'DEDUCTION')
                        JOIN stock_items origin ON origin.id = departure.stock_item_id
                             AND origin.material_id = received.material_id
                        WHERE entry.organization_id = :organizationId AND entry.type = 'ENTRY'
                          AND received.location_id = :worksiteId AND received.material_id IN (:materials)
                        """,
                new MapSqlParameterSource("organizationId", organizationId())
                        .addValue("worksiteId", worksiteId).addValue("materials", materialIds),
                (RowCallbackHandler) rs -> origins
                        .computeIfAbsent(rs.getObject("material_id", UUID.class), ignored -> new HashSet<>())
                        .add(rs.getObject("location_id", UUID.class)));
        return origins;
    }

    @Override
    public void saveReservation(StockReservation reservation) {
        jdbc.update("""
                        INSERT INTO stock_reservations (id, organization_id, stock_item_id, order_id, order_line_id,
                                                        quantity, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservation.id(), organizationId(), reservation.stockItemId(), reservation.orderId(),
                reservation.orderLineId(), reservation.quantity(), reservation.status().name(),
                Timestamp.from(reservation.createdAt()));
    }

    @Override
    public Optional<StockReservation> findReservation(UUID orderLineId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS + " WHERE r.organization_id = ? AND r.order_line_id = ? AND r.status = 'RESERVED'",
                RESERVATION, organizationId(), orderLineId).stream().findFirst();
    }

    @Override
    public List<StockReservation> findReservationsByOrder(UUID orderId) {
        return jdbc.query("SELECT " + RESERVATION_COLUMNS
                        + " WHERE r.organization_id = ? AND r.order_id = ? ORDER BY r.created_at, r.id",
                RESERVATION, organizationId(), orderId);
    }

    @Override
    public List<StockReservation> findReservationsByLocations(Collection<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT " + RESERVATION_COLUMNS
                        + " WHERE r.organization_id = :organizationId AND i.location_id IN (:locations)"
                        + " ORDER BY r.created_at DESC, r.id DESC",
                new MapSqlParameterSource("organizationId", organizationId()).addValue("locations", locationIds),
                RESERVATION);
    }

    @Override
    public boolean tryConsumeReservation(UUID orderLineId, Quantity quantity) {
        if (jdbc.update("""
                        UPDATE stock_reservations SET status = 'CONSUMED'
                        WHERE organization_id = ? AND order_line_id = ? AND status = 'RESERVED' AND quantity = ?
                        """, organizationId(), orderLineId, quantity.value()) == 1) {
            return true;
        }
        return jdbc.update("""
                        WITH remaining AS (
                            UPDATE stock_reservations SET quantity = quantity - ?
                            WHERE organization_id = ? AND order_line_id = ? AND status = 'RESERVED' AND quantity > ?
                            RETURNING organization_id, stock_item_id, order_id, order_line_id, created_at
                        )
                        INSERT INTO stock_reservations (id, organization_id, stock_item_id, order_id, order_line_id,
                                                        quantity, status, created_at)
                        SELECT ?, organization_id, stock_item_id, order_id, order_line_id, ?, 'CONSUMED', created_at
                        FROM remaining
                        """, quantity.value(), organizationId(), orderLineId, quantity.value(), TimeOrderedId.next(),
                quantity.value()) == 1;
    }

    @Override
    public boolean tryMarkReleased(UUID reservationId) {
        return jdbc.update("""
                        UPDATE stock_reservations SET status = 'RELEASED'
                        WHERE organization_id = ? AND id = ? AND status = 'RESERVED'
                        """, organizationId(), reservationId) == 1;
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }
}
