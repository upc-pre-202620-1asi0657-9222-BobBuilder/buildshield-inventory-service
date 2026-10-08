package pe.buildshield.inventory.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Puerto de las copias locales de organization (plan §7): obras, almacenes, materiales y asignaciones de la
 * organización del {@code TenantContext}. Las alimentan los eventos de {@code buildshield.organization}; no hay
 * llamadas síncronas a otro servicio.
 */
public interface SiteDirectory {

    String WORKSITE = "WORKSITE";
    String WAREHOUSE = "WAREHOUSE";

    Optional<Site> findLocation(UUID locationId);

    /** Obras o almacenes de la organización ({@link #WORKSITE} o {@link #WAREHOUSE}), por nombre. */
    List<Site> locations(String type);

    Optional<MaterialInfo> findMaterial(UUID materialId);

    Map<UUID, MaterialInfo> findMaterials(Collection<UUID> materialIds);

    /** ¿El usuario tiene una asignación vigente a la obra o el almacén? */
    boolean isAssigned(UUID userId, UUID locationId);

    /** Obras y almacenes con asignación vigente del usuario. */
    Set<UUID> assignedLocations(UUID userId);

    /** Obra o almacén. */
    record Site(UUID id, String type, String name, String address, boolean active) {

        public boolean isWarehouse() {
            return WAREHOUSE.equals(type);
        }

        public boolean isWorksite() {
            return WORKSITE.equals(type);
        }
    }

    /** Material del catálogo. */
    record MaterialInfo(UUID id, String sku, String name, String unit, BigDecimal tolerancePercent, boolean active) {
    }
}
