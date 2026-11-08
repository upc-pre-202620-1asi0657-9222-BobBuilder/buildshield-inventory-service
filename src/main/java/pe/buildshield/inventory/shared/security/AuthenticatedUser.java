package pe.buildshield.inventory.shared.security;

import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.security.Principal;

/** Principal de Spring Security para una petición autenticada con JWT. */
public record AuthenticatedUser(TenantInfo tenant, String tokenId) implements Principal {

    @Override
    public String getName() {
        return tenant.userId().toString();
    }
}
