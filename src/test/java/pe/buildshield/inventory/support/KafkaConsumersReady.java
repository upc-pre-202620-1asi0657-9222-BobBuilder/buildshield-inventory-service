package pe.buildshield.inventory.support;

import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Espera a que cada consumidor de inventory tenga asignadas sus particiones en el Kafka embebido antes de que una
 * prueba publique eventos. Con la máquina cargada, el primer ingreso al grupo de consumidores puede tardar más que
 * el tiempo de espera de cada paso; así esa demora no se descuenta de la primera prueba.
 */
public final class KafkaConsumersReady {

    /** Particiones de cada tópico en las pruebas ({@code @EmbeddedKafka(partitions = 3)}). */
    private static final int PARTITIONS = 3;
    private static final Duration MAX_WAIT = Duration.ofMinutes(4);

    private KafkaConsumersReady() {
    }

    public static void awaitAssignment(KafkaListenerEndpointRegistry registry) {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            await().atMost(MAX_WAIT).pollInSameThread().until(() -> container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().size() >= PARTITIONS);
        }
    }
}
