package pe.buildshield.inventory.acceptance;

/** Respuesta HTTP real del servicio: código, cuerpo como texto y la cabecera Idempotent-Replayed (si vino). */
public record ApiResponse(int status, String body, String replayed) {

    public ApiResponse(int status, String body) {
        this(status, body, null);
    }
}
