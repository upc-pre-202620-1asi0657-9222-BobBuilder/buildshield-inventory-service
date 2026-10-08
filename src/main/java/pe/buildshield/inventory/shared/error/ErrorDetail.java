package pe.buildshield.inventory.shared.error;

/** Detalle de un error, normalmente asociado a un campo de la petición. */
public record ErrorDetail(String field, String message) {

    public static ErrorDetail of(String message) {
        return new ErrorDetail(null, message);
    }
}
