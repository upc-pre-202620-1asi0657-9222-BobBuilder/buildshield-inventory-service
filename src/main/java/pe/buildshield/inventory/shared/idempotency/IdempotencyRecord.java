package pe.buildshield.inventory.shared.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Una operación confirmada. Su marca se conserva aunque se retire la respuesta. */
public record IdempotencyRecord(
        UUID organizationId, UUID key, String requestMethod, String requestPath,
        int responseStatus, String responseContentType, byte[] responseBody, Instant createdAt,
        UUID userId, String role, String requestFingerprint, String responseLocation, boolean responseExpired) {

    /** Constructor para datos históricos sin propietario ni huella; nunca se reproducen. */
    public IdempotencyRecord(UUID organizationId, UUID key, String method, String path, int status,
            String contentType, byte[] body, Instant createdAt) {
        this(organizationId, key, method, path, status, contentType, body, createdAt, null, null, null, null, false);
    }

    boolean isSameRequest(String method, String path) {
        return requestMethod.equals(method) && requestPath.equals(path);
    }

    boolean isSameRequest(String method, String path, String fingerprint) {
        return isSameRequest(method, path) && Objects.equals(requestFingerprint, fingerprint);
    }

    boolean belongsTo(UUID actor, String actorRole) {
        return actor.equals(userId) && actorRole.equals(role);
    }

    boolean isVerifiable() {
        return userId != null && role != null && requestFingerprint != null;
    }
}
