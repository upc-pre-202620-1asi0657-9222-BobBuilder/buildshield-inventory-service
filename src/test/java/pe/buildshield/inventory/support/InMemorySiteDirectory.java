package pe.buildshield.inventory.support;

import pe.buildshield.inventory.application.SiteDirectory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Copias de organization en memoria (obras, almacenes, materiales y asignaciones) para pruebas unitarias. */
public class InMemorySiteDirectory implements SiteDirectory {

    private final Map<UUID, Site> sites = new LinkedHashMap<>();
    private final Map<UUID, MaterialInfo> materials = new HashMap<>();
    private final Map<UUID, Set<UUID>> assignments = new HashMap<>();

    public Site warehouse(String name, boolean active) {
        return add(new Site(UUID.randomUUID(), WAREHOUSE, name, "Av. Argentina 2500", active));
    }

    public Site worksite(String name) {
        return add(new Site(UUID.randomUUID(), WORKSITE, name, "Av. Javier Prado 123", true));
    }

    public MaterialInfo material(String sku, boolean active) {
        MaterialInfo material = new MaterialInfo(UUID.randomUUID(), sku, "Material " + sku, "BAG",
                new BigDecimal("2.50"), active);
        materials.put(material.id(), material);
        return material;
    }

    public void assign(UUID userId, UUID locationId) {
        assignments.computeIfAbsent(userId, ignored -> new HashSet<>()).add(locationId);
    }

    private Site add(Site site) {
        sites.put(site.id(), site);
        return site;
    }

    @Override
    public Optional<Site> findLocation(UUID locationId) {
        return Optional.ofNullable(sites.get(locationId));
    }

    @Override
    public List<Site> locations(String type) {
        return sites.values().stream().filter(site -> site.type().equals(type))
                .sorted(Comparator.comparing(Site::name)).toList();
    }

    @Override
    public Optional<MaterialInfo> findMaterial(UUID materialId) {
        return Optional.ofNullable(materials.get(materialId));
    }

    @Override
    public Map<UUID, MaterialInfo> findMaterials(Collection<UUID> materialIds) {
        Map<UUID, MaterialInfo> found = new HashMap<>();
        materialIds.forEach(id -> findMaterial(id).ifPresent(material -> found.put(id, material)));
        return found;
    }

    @Override
    public boolean isAssigned(UUID userId, UUID locationId) {
        return assignedLocations(userId).contains(locationId);
    }

    @Override
    public Set<UUID> assignedLocations(UUID userId) {
        return assignments.getOrDefault(userId, Set.of());
    }
}
