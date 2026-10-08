package pe.buildshield.inventory.shared.error;

import java.util.List;

/** El recurso no existe o no pertenece a la organización. Se responde con HTTP 404. */
public class ResourceNotFoundException extends DomainException {

    public static final String DEFAULT_CODE = "NOT_FOUND";

    public ResourceNotFoundException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ResourceNotFoundException(String code, String message) {
        this(code, message, List.of());
    }

    public ResourceNotFoundException(String code, String message, List<ErrorDetail> details) {
        super(code, message, details);
    }
}
