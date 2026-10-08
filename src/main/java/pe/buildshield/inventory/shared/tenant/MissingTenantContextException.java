package pe.buildshield.inventory.shared.tenant;

/**
 * Se intentó acceder a datos de negocio sin una organización en el contexto. Es la falla segura
 * del filtro multiempresa: nunca se consulta sin organización.
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException() {
        super("No hay una organización en el contexto de la petición");
    }
}
