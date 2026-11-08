package pe.buildshield.inventory.domain.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Historial paginado de los lugares indicados, del más reciente al más antiguo (US40).
     *
     * @param page página desde 0
     * @param size tamaño de la página (mayor que cero)
     */
    MovementPage findMovementPage(Collection<UUID> locationIds, MovementFilter filter, int page, int size);

    /**
     * Almacenes de los que salió lo que la obra recibió de cada material: la entrada en obra (ENTRY) y la salida del
     * almacén (DISPATCH o DEDUCTION) comparten el despacho en {@code source_ref}.
     */
    Map<UUID, Set<UUID>> originWarehouses(UUID worksiteId, Set<UUID> materialIds);

    void saveReservation(StockReservation reservation);

    /** Fila vigente (RESERVED) de la línea de pedido; vacío si ya se consumió o liberó todo o no se reservó. */
    Optional<StockReservation> findReservation(UUID orderLineId);

    /** Filas de reserva de un pedido, en el orden en que se hicieron. */
    List<StockReservation> findReservationsByOrder(UUID orderId);

    /** Filas de reserva cuyo almacén está entre {@code locationIds}, de la más reciente a la más antigua. */
    List<StockReservation> findReservationsByLocations(Collection<UUID> locationIds);

    /**
     * Consume {@code quantity} de la fila vigente de la línea, solo si le queda suficiente: si la consume por completo
     * pasa a CONSUMED; si no, le resta lo consumido y agrega una fila CONSUMED con ello.
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
