package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Cantidad de material en movimiento: mayor que cero, con hasta 3 decimales (kg, m³, etc.). */
public record Quantity(BigDecimal value) {

    public static final int SCALE = 3;

    public Quantity {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > SCALE) {
            throw new ValidationException("INVALID_QUANTITY",
                    "La cantidad debe ser mayor que cero, con hasta 3 decimales",
                    List.of(new ErrorDetail("quantity", "mayor que cero, máximo 3 decimales")));
        }
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }
}
