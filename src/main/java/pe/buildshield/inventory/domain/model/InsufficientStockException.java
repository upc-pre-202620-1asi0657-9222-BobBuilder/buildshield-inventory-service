package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** No hay stock suficiente para descontar. Se lanza sin haber modificado nada. Se responde 409. */
public class InsufficientStockException extends ConflictException {

    public static final String CODE = "INSUFFICIENT_STOCK";

    private final BigDecimal available;
    private final BigDecimal requested;

    public InsufficientStockException(UUID locationId, UUID materialId, BigDecimal available, BigDecimal requested) {
        super(CODE, "Stock insuficiente: hay " + available.stripTrailingZeros().toPlainString() + " y se pidió "
                        + requested.stripTrailingZeros().toPlainString(),
                List.of(new ErrorDetail("materialId", materialId + " en " + locationId),
                        new ErrorDetail("available", available.toPlainString()),
                        new ErrorDetail("requested", requested.toPlainString())));
        this.available = available;
        this.requested = requested;
    }

    public BigDecimal available() {
        return available;
    }

    public BigDecimal requested() {
        return requested;
    }
}
