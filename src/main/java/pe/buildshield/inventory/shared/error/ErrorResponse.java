package pe.buildshield.inventory.shared.error;

import java.util.List;

/** Formato único de error de todas las APIs de BuildShield. */
public record ErrorResponse(String code, String message, List<ErrorDetail> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of());
    }
}
