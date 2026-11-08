package pe.buildshield.inventory.shared.correlation;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdLoggingEnvironmentPostProcessorTest {

    private final CorrelationIdLoggingEnvironmentPostProcessor processor =
            new CorrelationIdLoggingEnvironmentPostProcessor();

    @Test
    void adds_the_correlation_id_to_the_log_pattern_by_default() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.pattern.level")).contains("%X{correlationId:-}");
    }

    @Test
    void respects_a_pattern_defined_by_the_unit() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("unit", Map.of("logging.pattern.level", "%5p custom")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.pattern.level")).isEqualTo("%5p custom");
    }
}
