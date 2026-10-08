package pe.buildshield.inventory.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estado de un escenario compartido entre las clases de pasos: sesión del usuario actual, última
 * respuesta y los identificadores de lo creado, por nombre. Tiene scope de escenario.
 * <p>
 * Las peticiones van por HTTP real al servicio levantado en un puerto aleatorio.
 */
public class ScenarioSession {

    /** Clave del id de la organización del escenario. */
    public static final String ORGANIZATION = "__organization__";

    private final RestClient http;
    private final ObjectMapper json;

    private String accessToken;
    private String refreshToken;
    private String previousRefreshToken;
    private ApiResponse lastResponse;

    /** Identificadores por nombre visible: obras, almacenes, materiales (por SKU) y usuarios (por correo). */
    private final Map<String, UUID> ids = new HashMap<>();

    /** Últimos tokens (acceso y renovación) de cada usuario que inició sesión, por correo. */
    private final Map<String, String[]> sessions = new HashMap<>();

    /** Claves de idempotencia por nombre, para repetir una operación con la misma clave. */
    private final Map<String, String> keys = new HashMap<>();

    /** Textos guardados por nombre (por ejemplo, el sobre de un evento para enviarlo otra vez). */
    private final Map<String, String> texts = new HashMap<>();

    public ScenarioSession(String baseUrl, ObjectMapper json) {
        this.json = json;
        // JDK HttpClient: admite PATCH. Ningún código de estado se trata como error: lo verifica cada paso.
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** Envía la petición con el token de la sesión actual (si hay) y la guarda como última respuesta. */
    public ApiResponse send(ApiRequest request, Object body) throws Exception {
        lastResponse = perform(request, body, accessToken);
        return lastResponse;
    }

    /** Envía sin usar ni modificar la sesión ni la última respuesta. */
    public ApiResponse perform(ApiRequest request, Object body, String bearer) throws Exception {
        RestClient.RequestBodySpec spec = http.method(request.method()).uri(request.pathAndQuery());
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        if (request.idempotencyKey() != null) {
            spec.header("Idempotency-Key", request.idempotencyKey());
        }
        if (bearer != null) {
            spec.header("Authorization", "Bearer " + bearer);
        }
        return spec.exchange((req, response) -> new ApiResponse(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                response.getHeaders().getFirst("Idempotent-Replayed")));
    }

    public int status() {
        return lastResponse.status();
    }

    public JsonNode body() throws Exception {
        return read(lastResponse);
    }

    public JsonNode read(ApiResponse response) throws Exception {
        return json.readTree(response.body());
    }

    public void expectStatus(int expected) {
        assertThat(status()).as("respuesta: %s", lastResponse.body()).isEqualTo(expected);
    }

    public UUID id(String name) {
        UUID id = ids.get(name);
        assertThat(id).as("no se registró '%s' en el escenario", name).isNotNull();
        return id;
    }

    public void remember(String name) throws Exception {
        ids.put(name, UUID.fromString(body().path("id").asText()));
    }

    // Acceso por métodos: el bean es un proxy de scope de escenario y sus campos no se ven desde fuera.

    public String accessToken() {
        return accessToken;
    }

    public void accessToken(String value) {
        accessToken = value;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public void refreshToken(String value) {
        refreshToken = value;
    }

    public String previousRefreshToken() {
        return previousRefreshToken;
    }

    public void previousRefreshToken(String value) {
        previousRefreshToken = value;
    }

    public void lastResponse(ApiResponse value) {
        lastResponse = value;
    }

    public void put(String name, UUID id) {
        ids.put(name, id);
    }

    public void rememberSession(String email, String access, String refresh) {
        sessions.put(email, new String[]{access, refresh});
    }

    /** [token de acceso, token de renovación] del último inicio de sesión de ese correo. */
    public String[] sessionOf(String email) {
        String[] tokens = sessions.get(email);
        assertThat(tokens).as("%s no inició sesión en el escenario", email).isNotNull();
        return tokens;
    }

    public String key(String name) {
        return keys.computeIfAbsent(name, ignored -> UUID.randomUUID().toString());
    }

    public ApiResponse last() {
        return lastResponse;
    }

    public void putText(String name, String value) {
        texts.put(name, value);
    }

    public String text(String name) {
        String value = texts.get(name);
        assertThat(value).as("no se guardó '%s' en el escenario", name).isNotNull();
        return value;
    }
}
