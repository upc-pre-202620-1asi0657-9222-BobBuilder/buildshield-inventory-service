package pe.buildshield.inventory.shared.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** SHA-256 de los datos: objetos JSON ordenados, arrays conservan su orden. */
final class RequestFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RequestFingerprint() { }
    static String of(BufferedRequest request) {
        byte[] body = request.body();
        if (request.getContentType() != null && request.getContentType().toLowerCase(java.util.Locale.ROOT).contains("json")
                && body.length != 0) {
            try {
                body = JSON.writeValueAsBytes(canonical(JSON.readTree(body)));
            } catch (java.io.IOException ignored) {
                // MVC rechazará el JSON inválido; la respuesta fallida no se guarda.
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((request.getContentType() == null ? "" : request.getContentType())
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((request.getQueryString() == null ? "" : request.getQueryString()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            var result = JSON.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(child -> result.add(canonical(child)));
            return result;
        }
        return node;
    }
}
