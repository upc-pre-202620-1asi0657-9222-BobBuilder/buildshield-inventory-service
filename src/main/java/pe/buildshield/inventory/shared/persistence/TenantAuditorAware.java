package pe.buildshield.inventory.shared.persistence;

import org.springframework.data.domain.AuditorAware;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.util.Optional;
import java.util.UUID;

/** Usuario que audita los cambios: el del token, o el usuario de sistema en modo sistema. */
public class TenantAuditorAware implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        if (TenantContext.isSystem()) {
            return Optional.of(TenantContext.SYSTEM_USER_ID);
        }
        return TenantContext.current().map(TenantInfo::userId);
    }
}
