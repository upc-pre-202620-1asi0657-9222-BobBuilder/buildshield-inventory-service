package pe.buildshield.inventory.shared.tenant;

import java.util.Objects;
import java.util.UUID;

/** Organización, usuario y rol de quien hace la petición, tomados del token. */
public record TenantInfo(UUID organizationId, UUID userId, String role) {

    public TenantInfo {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
