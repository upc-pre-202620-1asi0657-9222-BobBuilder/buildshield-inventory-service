package pe.buildshield.inventory.shared.idempotency;

/** Puerto para revisar permisos actuales sin acoplar el kernel a los BC. */
public interface ReplayAuthorizer {
    boolean supports(String path);
    boolean allowed(String path, byte[] responseBody);
}
