package pe.buildshield.inventory.shared.error;

import java.util.List;

/**
 * Excepción de negocio. Es Java puro para que la capa de dominio de cada unidad pueda lanzarla
 * sin depender de Spring; {@code GlobalExceptionHandler} la traduce al código HTTP.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final List<ErrorDetail> details;

    protected DomainException(String code, String message, List<ErrorDetail> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }
}
