package pe.buildshield.inventory.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.spring.CucumberContextConfiguration;
import io.cucumber.spring.ScenarioScope;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import pe.buildshield.inventory.support.ContainersConfig;
import pe.buildshield.inventory.support.MutableClock;

import java.time.Instant;

/**
 * Contexto de los escenarios: servicio completo en un servidor HTTP real (puerto aleatorio), PostgreSQL real y
 * Kafka embebido. Obras, almacenes, materiales y asignaciones llegan como en producción: eventos de organization
 * en {@code buildshield.organization}; los de la saga, eventos de ordering en {@code buildshield.ordering}.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ContainersConfig.class, CucumberSpringConfiguration.AcceptanceBeans.class})
@EmbeddedKafka(kraft = true, partitions = 3, topics = {"buildshield.inventory", "buildshield.organization", "buildshield.ordering"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public class CucumberSpringConfiguration {

    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptanceBeans {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.now());
        }

        @Bean
        @ScenarioScope
        ScenarioSession scenarioSession(Environment environment, ObjectMapper json) {
            return new ScenarioSession("http://localhost:" + environment.getRequiredProperty("local.server.port"), json);
        }
    }
}
