package pe.buildshield.inventory.acceptance;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

/** Petición HTTP de un paso: método, ruta y parámetros de consulta. */
public final class ApiRequest {

    private final HttpMethod method;
    private final UriComponentsBuilder uri;
    private String idempotencyKey;

    private ApiRequest(HttpMethod method, String path) {
        this.method = method;
        this.uri = UriComponentsBuilder.fromPath(path);
    }

    public static ApiRequest get(String path) {
        return new ApiRequest(HttpMethod.GET, path);
    }

    public static ApiRequest post(String path) {
        return new ApiRequest(HttpMethod.POST, path);
    }

    public static ApiRequest patch(String path) {
        return new ApiRequest(HttpMethod.PATCH, path);
    }

    public static ApiRequest put(String path) {
        return new ApiRequest(HttpMethod.PUT, path);
    }

    /** Usa esta clave de idempotencia en vez de una nueva. */
    public ApiRequest idempotencyKey(String key) {
        idempotencyKey = key;
        return this;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    public ApiRequest param(String name, String value) {
        uri.queryParam(name, value);
        return this;
    }

    HttpMethod method() {
        return method;
    }

    String pathAndQuery() {
        return uri.encode().build().toUriString();
    }
}
