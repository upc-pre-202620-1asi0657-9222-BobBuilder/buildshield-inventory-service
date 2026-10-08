package pe.buildshield.inventory.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * Clase base de toda entidad de negocio: lleva la columna {@code organization_id}.
 *
 * <p>{@link TenantId} hace que Hibernate agregue la organización del {@code TenantContext} a
 * <b>todas</b> las lecturas (consultas JPQL, criteria, {@code findAll} y también la carga por id) y
 * que la asigne al insertar. Si no hay organización en el contexto, la sesión no se abre y la
 * operación falla con {@code MissingTenantContextException}.
 */
@MappedSuperclass
public abstract class OrganizationScopedEntity {

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    public UUID getOrganizationId() {
        return organizationId;
    }
}
