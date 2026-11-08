package pe.buildshield.inventory.shared.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import pe.buildshield.inventory.shared.persistence.TenantAuditorAware;

import java.time.Clock;
import java.util.Optional;

/**
 * Auditoría de {@code AuditableAbstractAggregateRoot}: usuario del {@code TenantContext} y hora del
 * reloj de la aplicación. Las unidades no deben declarar su propio {@code @EnableJpaAuditing}.
 */
@AutoConfiguration(after = {HibernateJpaAutoConfiguration.class, BuildshieldClockAutoConfiguration.class})
@ConditionalOnBean(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "buildshield.persistence", name = "enabled", matchIfMissing = true)
@EnableJpaAuditing(auditorAwareRef = "buildshieldAuditorAware", dateTimeProviderRef = "buildshieldDateTimeProvider")
public class BuildshieldJpaAuditingAutoConfiguration {

    @Bean
    TenantAuditorAware buildshieldAuditorAware() {
        return new TenantAuditorAware();
    }

    @Bean
    DateTimeProvider buildshieldDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
