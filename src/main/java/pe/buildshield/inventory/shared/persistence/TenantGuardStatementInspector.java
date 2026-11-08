package pe.buildshield.inventory.shared.persistence;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import pe.buildshield.inventory.shared.tenant.MissingTenantContextException;
import pe.buildshield.inventory.shared.tenant.TenantContext;

/**
 * Falla segura del filtro multiempresa: Hibernate no ejecuta ningún SQL si no hay organización en el
 * {@link TenantContext} (salvo en modo sistema). Actúa al ejecutar, no al abrir la sesión, para que
 * la aplicación pueda arrancar y validar sus consultas.
 */
public class TenantGuardStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        if (!TenantContext.isSystem() && TenantContext.current().isEmpty()) {
            throw new MissingTenantContextException();
        }
        return sql;
    }
}
