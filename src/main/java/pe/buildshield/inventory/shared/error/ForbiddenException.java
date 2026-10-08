package pe.buildshield.inventory.shared.error;

import java.util.List;

/** La operación no está permitida para el usuario. Se responde con HTTP 403. */
public class ForbiddenException extends DomainException {

    public static final String DEFAULT_CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ForbiddenException(String code, String message) {
        this(code, message, List.of());
    }

    public ForbiddenException(String code, String message, List<ErrorDetail> details) {
        super(code, message, details);
    }
}
