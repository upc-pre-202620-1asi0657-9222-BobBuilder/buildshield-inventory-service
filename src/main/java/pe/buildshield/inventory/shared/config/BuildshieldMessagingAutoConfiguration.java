package pe.buildshield.inventory.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import pe.buildshield.inventory.shared.messaging.IdempotentEventProcessor;
import pe.buildshield.inventory.shared.messaging.OutboxWriter;
import pe.buildshield.inventory.shared.messaging.ProcessedEvents;

import java.time.Clock;

/**
 * Mensajería del kernel: outbox, registro de eventos procesados, procesador idempotente, manejo de
 * errores de los consumidores y el tópico propio del servicio (3 particiones).
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class, BuildshieldClockAutoConfiguration.class},
        afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "buildshield.messaging", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BuildshieldProperties.class)
public class BuildshieldMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BuildshieldMessagingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        return new OutboxWriter(jdbc, json, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEvents processedEvents(JdbcTemplate jdbc, Clock clock) {
        return new ProcessedEvents(jdbc, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotentEventProcessor idempotentEventProcessor(ProcessedEvents processedEvents,
            PlatformTransactionManager transactionManager, ObjectMapper json) {
        return new IdempotentEventProcessor(processedEvents, new TransactionTemplate(transactionManager), json);
    }

    /**
     * Un evento que falla se reintenta 5 veces con 1 s de espera; después se registra y se salta para no
     * bloquear la partición. Un mensaje que no es un sobre válido no se reintenta.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler buildshieldKafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> log.error(
                "Evento descartado tras los reintentos: tópico={}, partición={}, offset={}, clave={}: {}",
                record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage()),
                new FixedBackOff(1000L, 5L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "buildshield.messaging", name = "topic")
    NewTopic buildshieldOwnTopic(BuildshieldProperties properties) {
        return new NewTopic(properties.getMessaging().getTopic(), properties.getMessaging().getPartitions(), (short) 1);
    }
}
