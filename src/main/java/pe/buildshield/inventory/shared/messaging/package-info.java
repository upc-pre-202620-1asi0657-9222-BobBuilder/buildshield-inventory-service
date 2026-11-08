/**
 * Eventos de integración entre microservicios: sobre JSON común ({@link pe.buildshield.inventory.shared.messaging.EventEnvelope}),
 * Transactional Outbox ({@link pe.buildshield.inventory.shared.messaging.OutboxWriter} y
 * {@link pe.buildshield.inventory.shared.messaging.OutboxPublisher}) y consumo idempotente
 * ({@link pe.buildshield.inventory.shared.messaging.IdempotentEventProcessor}). Entrega al menos una vez.
 */
package pe.buildshield.inventory.shared.messaging;
