package pe.buildshield.inventory.shared.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Almacén de claves de idempotencia. Todas las operaciones se llaman dentro de la transacción que
 * abre {@link IdempotencyKeyFilter}, la misma en la que ocurre el efecto de negocio.
 */
public interface IdempotencyStore {

    /**
     * Reserva la clave hasta que termine la transacción actual, sin esperar.
     *
     * @return {@code false} si otra transacción la tiene reservada (operación en curso)
     */
    boolean tryLock(UUID organizationId, UUID key);

    /** Busca incluso marcas históricas o con respuesta vencida: jamás permite duplicar el efecto. */
    Optional<IdempotencyRecord> find(UUID organizationId, UUID key);

    /** Inserta la operación confirmada. Nunca reemplaza una identidad previa. */
    void save(IdempotencyRecord record);

    /** Retira respuestas anteriores a {@code threshold}; conserva sus marcas y devuelve cuántas retiró. */
    int retireResponsesCreatedBefore(Instant threshold);
}
