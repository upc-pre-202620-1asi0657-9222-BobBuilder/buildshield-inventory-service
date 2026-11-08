package pe.buildshield.inventory.shared.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activa los trabajos periódicos del servicio (publicador del outbox y purgas).
 * Las pruebas lo apagan con {@code buildshield.scheduling.enabled=false} para invocarlos a mano.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "buildshield.scheduling", name = "enabled", matchIfMissing = true)
@EnableScheduling
public class BuildshieldSchedulingAutoConfiguration {
}
