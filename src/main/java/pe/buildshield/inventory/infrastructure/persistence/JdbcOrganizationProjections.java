package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import pe.buildshield.inventory.application.SiteDirectory;
import pe.buildshield.inventory.shared.tenant.TenantContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Copias locales de organization (plan §7): {@code location_projection}, {@code material_projection} y
 * {@code assignment_projection}. Las escribe {@code OrganizationEventConsumer} con cada evento de
 * {@code buildshield.organization}; las lecturas filtran por la organización del {@code TenantContext}.
 */
@Component
public class JdbcOrganizationProjections implements SiteDirectory {

    private static final RowMapper<Site> SITE = (rs, row) -> new Site(rs.getObject("location_id", UUID.class),
            rs.getString("type"), rs.getString("name"), rs.getString("address"), rs.getBoolean("active"));

    private static final RowMapper<MaterialInfo> MATERIAL = (rs, row) -> new MaterialInfo(
            rs.getObject("material_id", UUID.class), rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
            rs.getBigDecimal("tolerance_percent"), rs.getBoolean("active"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    JdbcOrganizationProjections(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    @Override
    public Optional<Site> findLocation(UUID locationId) {
        return jdbc.query("""
                SELECT location_id, type, name, address, active FROM location_projection
                WHERE location_id = ? AND organization_id = ?""", SITE, locationId, organizationId())
                .stream().findFirst();
    }

    @Override
    public List<Site> locations(String type) {
        return jdbc.query("""
                SELECT location_id, type, name, address, active FROM location_projection
                WHERE organization_id = ? AND type = ? ORDER BY name, location_id""", SITE, organizationId(), type);
    }

    @Override
    public Optional<MaterialInfo> findMaterial(UUID materialId) {
        return jdbc.query("""
                SELECT material_id, sku, name, unit, tolerance_percent, active FROM material_projection
                WHERE material_id = ? AND organization_id = ?""", MATERIAL, materialId, organizationId())
                .stream().findFirst();
    }

    @Override
    public Map<UUID, MaterialInfo> findMaterials(Collection<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        return named.query("""
                        SELECT material_id, sku, name, unit, tolerance_percent, active FROM material_projection
                        WHERE organization_id = :organizationId AND material_id IN (:ids)""",
                new MapSqlParameterSource("organizationId", organizationId()).addValue("ids", new HashSet<>(materialIds)),
                MATERIAL).stream().collect(Collectors.toMap(MaterialInfo::id, Function.identity()));
    }

    @Override
    public boolean isAssigned(UUID userId, UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assignment_projection
                               WHERE organization_id = ? AND user_id = ? AND location_id = ? AND active)""",
                Boolean.class, organizationId(), userId, locationId));
    }

    @Override
    public Set<UUID> assignedLocations(UUID userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT location_id FROM assignment_projection WHERE organization_id = ? AND user_id = ? AND active""",
                UUID.class, organizationId(), userId));
    }

    /** {@code LocationRegistered} o {@code LocationUpdated}. */
    public void upsertLocation(UUID organizationId, UUID locationId, String type, String name, String address,
            boolean active) {
        jdbc.update("""
                INSERT INTO location_projection (location_id, organization_id, type, name, address, active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (location_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name,
                    address = EXCLUDED.address, active = EXCLUDED.active, updated_at = EXCLUDED.updated_at""",
                locationId, organizationId, type, name, address, active, now());
    }

    /** {@code MaterialRegistered} o {@code MaterialUpdated}. */
    public void upsertMaterial(UUID organizationId, UUID materialId, String sku, String name, String unit,
            BigDecimal tolerancePercent, boolean active) {
        jdbc.update("""
                INSERT INTO material_projection (material_id, organization_id, sku, name, unit, tolerance_percent,
                                                 active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (material_id) DO UPDATE SET sku = EXCLUDED.sku, name = EXCLUDED.name, unit = EXCLUDED.unit,
                    tolerance_percent = EXCLUDED.tolerance_percent, active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at""",
                materialId, organizationId, sku, name, unit, tolerancePercent, active, now());
    }

    /** {@code StaffAssigned} (activa) o {@code StaffUnassigned} (inactiva). */
    public void upsertAssignment(UUID organizationId, UUID assignmentId, UUID userId, UUID locationId,
            String locationType, boolean active) {
        jdbc.update("""
                INSERT INTO assignment_projection (assignment_id, organization_id, user_id, location_id, location_type,
                                                   active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (assignment_id) DO UPDATE SET user_id = EXCLUDED.user_id,
                    location_id = EXCLUDED.location_id, location_type = EXCLUDED.location_type,
                    active = EXCLUDED.active, updated_at = EXCLUDED.updated_at""",
                assignmentId, organizationId, userId, locationId, locationType, active, now());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static UUID organizationId() {
        return TenantContext.require().organizationId();
    }
}
