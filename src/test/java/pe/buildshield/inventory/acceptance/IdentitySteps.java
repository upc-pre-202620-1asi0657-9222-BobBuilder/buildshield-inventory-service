package pe.buildshield.inventory.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pe.buildshield.inventory.shared.messaging.Topics;
import pe.buildshield.inventory.shared.security.JwtTokenIssuer;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.TestEvents;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pasos de identidad y de los datos de organization. Los usuarios solo existen en el token (lo emitiría iam con
 * la clave de prueba); obras, almacenes, materiales y asignaciones llegan como en producción: eventos de
 * {@code buildshield.organization} que inventory copia en sus proyecciones. Cada paso espera la copia.
 */
public class IdentitySteps {

    static final Duration EVENT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ScenarioSession session;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    /** Usuarios del escenario por correo. */
    private final Map<String, TenantInfo> users = new HashMap<>();

    @Dado("que la organización {string} con RUC {string} está registrada con el administrador {string} y contraseña {string}")
    public void organizationRegistered(String legalName, String ruc, String adminEmail, String password) {
        UUID organizationId = UUID.randomUUID();
        session.put(ScenarioSession.ORGANIZATION, organizationId);
        user(adminEmail, "ADMINISTRATOR");
    }

    @Dado("que creé el usuario {string} con correo {string}, rol {string} y contraseña {string}")
    public void userCreated(String fullName, String email, String role, String password) {
        user(email, role(role));
    }

    @Dado("que {string} inició sesión con la contraseña {string}")
    public void signedIn(String email, String password) {
        TenantInfo user = users.get(email);
        assertThat(user).as("el usuario %s no existe en el escenario", email).isNotNull();
        session.accessToken(issuer.issue(user).value());
    }

    @Dado("que registré el almacén {string}")
    public void warehouseRegistered(String name) {
        location(name, "WAREHOUSE", true);
    }

    @Dado("que registré la obra {string}")
    public void worksiteRegistered(String name) {
        location(name, "WORKSITE", true);
    }

    @Dado("que desactivé el almacén {string}")
    public void warehouseDeactivated(String name) {
        UUID id = session.id(name);
        publish(TestEvents.location(json, "LocationUpdated", organization(), id, "WAREHOUSE", name, false));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT active FROM location_projection WHERE location_id = ?", Boolean.class, id)));
    }

    @Dado("que registré el material {string} con SKU {string}, unidad {string} y tolerancia de merma {string} %")
    public void materialRegistered(String name, String sku, String unit, String tolerance) {
        UUID id = UUID.randomUUID();
        session.put(sku.toUpperCase(), id);
        publish(TestEvents.material(json, "MaterialRegistered", organization(), id, sku.toUpperCase(), name, unit(unit),
                tolerance, true));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> count("material_projection", "material_id", id) == 1);
    }

    @Dado("que retiré del catálogo el material {string}")
    public void materialRetired(String sku) {
        UUID id = session.id(sku.toUpperCase());
        publish(TestEvents.material(json, "MaterialUpdated", organization(), id, sku.toUpperCase(), sku, "BAG", "1",
                false));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT active FROM material_projection WHERE material_id = ?", Boolean.class, id)));
    }

    @Dado("que asigné a {string} al almacén {string}")
    public void assignedToWarehouse(String email, String warehouse) {
        assign(email, warehouse, "WAREHOUSE");
    }

    @Dado("que asigné a {string} a la obra {string}")
    public void assignedToWorksite(String email, String worksite) {
        assign(email, worksite, "WORKSITE");
    }

    @Entonces("la respuesta tiene estado {int}")
    public void responseStatus(int status) {
        session.expectStatus(status);
    }

    @Entonces("la operación es rechazada con estado {int}")
    public void rejected(int status) {
        session.expectStatus(status);
    }

    @Entonces("la operación es rechazada con estado {int} y código {string}")
    public void rejectedWithCode(int status, String code) throws Exception {
        session.expectStatus(status);
        assertThat(session.body().path("code").asText()).isEqualTo(code);
    }

    private void user(String email, String role) {
        UUID userId = UUID.randomUUID();
        users.put(email, new TenantInfo(organization(), userId, role));
        session.put(email, userId);
    }

    private void location(String name, String type, boolean active) {
        UUID id = UUID.randomUUID();
        session.put(name, id);
        publish(TestEvents.location(json, "LocationRegistered", organization(), id, type, name, active));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> count("location_projection", "location_id", id) == 1);
    }

    private void assign(String email, String site, String type) {
        UUID assignmentId = UUID.randomUUID();
        publish(TestEvents.assignment(json, "StaffAssigned", organization(), assignmentId, session.id(email),
                session.id(site), type));
        await().pollInSameThread().atMost(EVENT_TIMEOUT).until(() -> count("assignment_projection", "assignment_id", assignmentId) == 1);
    }

    private UUID organization() {
        return session.id(ScenarioSession.ORGANIZATION);
    }

    private void publish(String envelope) {
        try {
            kafka.send(Topics.ORGANIZATION, json.readTree(envelope).get("aggregateId").asText(), envelope).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long count(String table, String column, UUID id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    /** "encargado de obra" → SITE_MANAGER; "encargado de almacén" → WAREHOUSE_MANAGER. */
    private static String role(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "administrador" -> "ADMINISTRATOR";
            case "encargado de obra" -> "SITE_MANAGER";
            case "encargado de almacén", "encargado de almacen" -> "WAREHOUSE_MANAGER";
            default -> displayName;
        };
    }

    /** "bolsa" → BAG; "kilogramo" → KG; "metro cúbico" → M3; si no, tal cual. */
    private static String unit(String displayName) {
        return switch (displayName.trim().toLowerCase()) {
            case "bolsa" -> "BAG";
            case "kilogramo" -> "KG";
            case "metro cúbico" -> "M3";
            case "unidad" -> "UNIT";
            default -> displayName.toUpperCase();
        };
    }
}
