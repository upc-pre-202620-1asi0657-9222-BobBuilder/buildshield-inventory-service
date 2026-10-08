package pe.buildshield.inventory.infrastructure.security;

import org.springframework.stereotype.Component;
import pe.buildshield.inventory.shared.security.RevokedTokenStore;

import java.util.UUID;

/**
 * Revocación de tokens para el filtro JWT del kernel compartido. Inventory no guarda copia de los usuarios ni la
 * lista de tokens cerrados (viven en iam, y organization valida el estado del usuario): aquí el token vale hasta
 * que vence (15 minutos). La firma, el emisor, la vigencia y el rol se validan igual en cada petición (QAS04).
 */
@Component
class StatelessTokenGuard implements RevokedTokenStore {

    @Override
    public boolean isRevoked(String tokenId) {
        return false;
    }

    @Override
    public boolean isUserBlocked(UUID userId, String role) {
        return false;
    }
}
