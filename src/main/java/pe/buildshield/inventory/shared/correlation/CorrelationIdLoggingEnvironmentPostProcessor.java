package pe.buildshield.inventory.shared.correlation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Agrega el identificador de correlación al patrón de log, con la menor prioridad para que
 * una unidad pueda definir su propio {@code logging.pattern.level}.
 */
public class CorrelationIdLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "logging.pattern.level";
    static final String PATTERN = "%5p [%X{" + CorrelationId.MDC_KEY + ":-}]";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources()
                .addLast(new MapPropertySource("buildshieldCorrelationLogging", Map.of(PROPERTY, PATTERN)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
