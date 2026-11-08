package pe.buildshield.inventory.domain.model;

/**
 * Tipo de entrada de material a un almacén (US40, T90): el inventario inicial (una sola vez por material y
 * almacén) o una reposición. Cada uno deja su propio tipo de movimiento en el historial.
 */
public enum EntryType {

    INITIAL(StockMovement.Type.INITIAL),
    REPLENISHMENT(StockMovement.Type.REPLENISHMENT);

    private final StockMovement.Type movementType;

    EntryType(StockMovement.Type movementType) {
        this.movementType = movementType;
    }

    public StockMovement.Type movementType() {
        return movementType;
    }
}
