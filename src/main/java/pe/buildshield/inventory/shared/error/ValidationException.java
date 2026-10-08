package pe.buildshield.inventory.shared.error;

import java.util.List;

/** Datos inválidos o regla de negocio incumplida. Se responde con HTTP 400. */
public class ValidationException extends DomainException {

    public static final String DEFAULT_CODE = "VALIDATION_ERROR";

    public ValidationException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ValidationException(String code, String message) {
        this(code, message, List.of());
    }

    public ValidationException(String code, String message, List<ErrorDetail> details) {
        super(code, message, details);
    }
}
