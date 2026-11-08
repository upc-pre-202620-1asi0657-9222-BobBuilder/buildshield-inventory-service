package pe.buildshield.inventory.acceptance;

import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import pe.buildshield.inventory.support.KafkaConsumersReady;
import pe.buildshield.inventory.support.MutableClock;
import pe.buildshield.inventory.support.TestDatabase;

import java.time.Instant;

/**
 * Antes de cada escenario: consumidores de Kafka con sus particiones asignadas, base vacía (tablas del servicio) y
 * reloj en la hora actual.
 */
public class DatabaseHooks {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Autowired
    MutableClock clock;

    @Before(order = 0)
    public void resetState() {
        KafkaConsumersReady.awaitAssignment(listeners);
        TestDatabase.reset(jdbc);
        clock.setTo(Instant.now());
    }
}
