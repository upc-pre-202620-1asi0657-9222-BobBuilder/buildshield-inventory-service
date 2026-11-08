package pe.buildshield.inventory.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;

/** PostgreSQL real: contenedor 16 o base temporal externa explícita ({@code inventory_it}). */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfig {

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "buildshield.test.external-database", havingValue = "false", matchIfMissing = true)
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    @ConditionalOnProperty(name = "buildshield.test.external-database", havingValue = "true")
    JdbcConnectionDetails externalPostgres(Environment env) {
        return ExternalPostgres.connection(env, "inventory");
    }
}
