package pe.buildshield.inventory.shared.error;

import java.util.List;

/** El estado actual del recurso impide la operación. Se responde con HTTP 409. */
public class ConflictException extends DomainException {

    public static final String DEFAULT_CODE = "CONFLICT";

    public ConflictException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ConflictException(String code, String message) {
        this(code, message, List.of());
    }

    public ConflictException(String code, String message, List<ErrorDetail> details) {
        super(code, message, details);
    }
}
