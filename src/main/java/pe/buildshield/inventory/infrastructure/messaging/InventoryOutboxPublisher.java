package pe.buildshield.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.inventory.shared.config.BuildshieldProperties;
import pe.buildshield.inventory.shared.messaging.OutboxPublisher;

import java.time.Clock;

/**
 * Productor Kafka de inventory: cada 500 ms envía al tópico {@code buildshield.inventory} los eventos pendientes
 * del outbox ({@code StockReserved}, {@code StockReservationRejected}, {@code StockDeducted},
 * {@code StockReceived}, {@code StockReleased} y {@code StockEntryRegistered}).
 */
@Component
public class InventoryOutboxPublisher extends OutboxPublisher {

    public InventoryOutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            PlatformTransactionManager transactionManager, Clock clock, BuildshieldProperties properties) {
        super(jdbc, kafka, json, new TransactionTemplate(transactionManager), clock,
                properties.getMessaging().getTopic(), properties.getMessaging().getBatchSize(),
                properties.getMessaging().getSendTimeout());
    }

    @Scheduled(fixedDelayString = "${buildshield.messaging.outbox-interval:PT0.5S}")
    public void publish() {
        publishPending();
    }
}
