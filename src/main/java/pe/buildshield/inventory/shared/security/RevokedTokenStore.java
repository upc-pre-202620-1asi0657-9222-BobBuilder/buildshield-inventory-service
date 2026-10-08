package pe.buildshield.inventory.shared.security;

/**
 * Lista de revocación de tokens (por ejemplo, al cerrar sesión). Cada unidad la implementa con
 * su propio almacenamiento.
 */
public interface RevokedTokenStore {

    /** @param tokenId valor del claim {@code jti} */
    boolean isRevoked(String tokenId);

    /**
     * ¿El usuario ya no puede usar los tokens emitidos con ese rol? Por ejemplo, porque lo desactivaron
     * o le cambiaron el rol después de emitir el token. Por defecto, no.
     */
    default boolean isUserBlocked(java.util.UUID userId, String role) {
        return false;
    }
}
