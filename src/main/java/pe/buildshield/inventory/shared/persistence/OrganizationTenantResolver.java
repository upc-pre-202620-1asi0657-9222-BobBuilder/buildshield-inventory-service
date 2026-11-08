package pe.buildshield.inventory.shared.persistence;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import pe.buildshield.inventory.shared.tenant.TenantContext;

import java.util.UUID;

/**
 * Entrega a Hibernate la organización del {@link TenantContext}.
 *
 * <ul>
 *   <li>Con contexto: la organización del token.</li>
 *   <li>En modo sistema: un tenant raíz que no filtra, solo para procesos internos.</li>
 *   <li>Sin contexto: un tenant que no corresponde a ninguna organización. La sesión puede abrirse
 *       (Spring Data valida las consultas al arrancar), pero {@link TenantGuardStatementInspector}
 *       impide ejecutar cualquier SQL: nunca se consulta sin organización.</li>
 * </ul>
 */
public class OrganizationTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** Identificador del tenant raíz usado en modo sistema. No es una organización real. */
    public static final UUID SYSTEM_TENANT = new UUID(0L, 0L);

    /** Tenant de una sesión abierta sin organización. No es una organización real. */
    public static final UUID NO_TENANT = new UUID(-1L, -1L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return SYSTEM_TENANT;
        }
        return TenantContext.current().map(info -> info.organizationId()).orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return SYSTEM_TENANT.equals(tenantId);
    }
}
