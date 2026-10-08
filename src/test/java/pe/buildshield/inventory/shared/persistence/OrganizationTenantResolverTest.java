package pe.buildshield.inventory.shared.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.buildshield.inventory.shared.tenant.MissingTenantContextException;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationTenantResolverTest {

    private final OrganizationTenantResolver resolver = new OrganizationTenantResolver();
    private final TenantGuardStatementInspector guard = new TenantGuardStatementInspector();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void resolves_the_organization_of_the_context() {
        UUID organizationId = UUID.randomUUID();
        TenantContext.set(new TenantInfo(organizationId, UUID.randomUUID(), "SUPERVISOR"));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(organizationId);
        assertThat(resolver.isRoot(organizationId)).isFalse();
        assertThat(guard.inspect("select 1")).isEqualTo("select 1");
    }

    @Test
    void without_context_the_session_opens_but_no_sql_runs() {
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(OrganizationTenantResolver.NO_TENANT);
        assertThat(resolver.isRoot(OrganizationTenantResolver.NO_TENANT)).isFalse();
        assertThatThrownBy(() -> guard.inspect("select * from test_notes"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void system_mode_resolves_the_root_tenant_and_may_run_sql() {
        UUID tenant = TenantContext.callAsSystem(resolver::resolveCurrentTenantIdentifier);

        assertThat(tenant).isEqualTo(OrganizationTenantResolver.SYSTEM_TENANT);
        assertThat(resolver.isRoot(tenant)).isTrue();
        assertThat(resolver.validateExistingCurrentSessions()).isFalse();
        assertThat(TenantContext.callAsSystem(() -> guard.inspect("select 1"))).isEqualTo("select 1");
    }
}
