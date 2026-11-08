package pe.buildshield.inventory.shared.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Reloj único (UTC) para auditoría, expiración de tokens e idempotencia; las pruebas lo reemplazan. */
@AutoConfiguration
public class BuildshieldClockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
