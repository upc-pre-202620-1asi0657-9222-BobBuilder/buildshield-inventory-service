package pe.buildshield.inventory.shared.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import pe.buildshield.inventory.shared.persistence.OrganizationTenantResolver;
import pe.buildshield.inventory.shared.persistence.TenantGuardStatementInspector;

/**
 * Filtro multiempresa de Hibernate basado en el {@code TenantContext}. Debe configurarse antes de
 * Hibernate para que el resolver llegue a la fábrica de sesiones.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "buildshield.persistence", name = "enabled", matchIfMissing = true)
public class BuildshieldPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OrganizationTenantResolver organizationTenantResolver() {
        return new OrganizationTenantResolver();
    }

    @Bean
    HibernatePropertiesCustomizer organizationTenantHibernateCustomizer(OrganizationTenantResolver resolver) {
        return properties -> {
            properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
            properties.put(AvailableSettings.STATEMENT_INSPECTOR, new TenantGuardStatementInspector());
        };
    }
}
