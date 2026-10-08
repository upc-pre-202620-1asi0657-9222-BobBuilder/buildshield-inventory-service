package pe.buildshield.inventory.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Servicio completo contra PostgreSQL real (Testcontainers o la base temporal externa explícita
 * {@code inventory_it}) y Kafka embebido ({@code @EmbeddedKafka}, KRaft) con los tópicos que usa inventory.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainersConfig.class)
@EmbeddedKafka(kraft = true, partitions = 3,
        topics = {"buildshield.inventory", "buildshield.organization", "buildshield.ordering"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public @interface InventoryIntegrationTest {
}
