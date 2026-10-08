package pe.buildshield.inventory.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Existencias de la organización del contexto. Las lecturas para reservar, consumir o liberar van siempre a la
 * base, sin caché, y las escrituras son UPDATE condicionados por la versión leída (bloqueo optimista, QAS02).
 */
public interface StockRepository {

    Optional<StockItem> find(UUID locationId, UUID materialId);

    /** Ítems de los lugares indicados, ordenados por lugar y material. */
    List<StockItem> findByLocations(Collection<UUID> locationIds);

    /**
     * Descuenta solo si la versión sigue siendo {@code expectedVersion} y hay cantidad suficiente.
     *
     * @return {@code false} si otra operación cambió el ítem o ya no alcanza (no hubo efecto)
     */
    boolean tryDeduct(UUID stockItemId, long expectedVersion, Quantity quantity);

    /**
     * Pasa {@code quantity} de disponible a reservado, solo si la versión sigue siendo
     * {@code expectedVersion} y hay disponible suficiente.
     *
     * @return {@code false} si otra operación cambió el ítem o ya no alcanza (no hubo efecto)
     */
    boolean tryReserve(UUID stockItemId, long expectedVersion, Quantity quantity);

    /**
     * Saca {@code quantity} de lo reservado (salida por despacho), solo si la versión sigue siendo
     * {@code expectedVersion} y hay reservado suficiente.
     *
     * @return {@code false} si otra operación cambió el ítem o ya no alcanza (no hubo efecto)
     */
    boolean tryConsumeReserved(UUID stockItemId, long expectedVersion, Quantity quantity);

    /**
     * Devuelve {@code quantity} de lo reservado a lo disponible, solo si la versión sigue siendo
     * {@code expectedVersion} y hay reservado suficiente.
     *
     * @return {@code false} si otra operación cambió el ítem (no hubo efecto)
     */
    boolean tryRelease(UUID stockItemId, long expectedVersion, Quantity quantity);

    /** Suma al ítem (lo crea si no existe) y devuelve el ítem resultante. */
    StockItem add(UUID locationId, UUID materialId, Quantity quantity);

    void record(StockMovement movement);

    /** Movimientos de los lugares indicados, del más reciente al más antiguo; {@code materialId} es opcional. */
    List<RecordedMovement> findMovements(Collection<UUID> locationIds, UUID materialId, int limit);

    void saveReservation(StockReservation reservation);

    Optional<StockReservation> findReservation(UUID orderLineId);

    /** Reservas de un pedido, en el orden en que se hicieron. */
    List<StockReservation> findReservationsByOrder(UUID orderId);

    /** Reservas cuyo almacén está entre {@code locationIds}, de la más reciente a la más antigua. */
    List<StockReservation> findReservationsByLocations(Collection<UUID> locationIds);

    /**
     * Suma {@code quantity} a lo consumido de la reserva de la línea, solo si sigue vigente y le queda
     * suficiente; si queda consumida por completo pasa a CONSUMED.
     *
     * @return {@code false} si la reserva ya no admite ese consumo (no hubo efecto)
     */
    boolean tryConsumeReservation(UUID orderLineId, Quantity quantity);

    /**
     * Marca la reserva RELEASED, solo si sigue RESERVED.
     *
     * @return {@code false} si ya no estaba vigente (no hubo efecto)
     */
    boolean tryMarkReleased(UUID reservationId);
}
