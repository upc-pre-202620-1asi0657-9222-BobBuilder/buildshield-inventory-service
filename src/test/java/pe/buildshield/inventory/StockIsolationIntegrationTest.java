package pe.buildshield.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pe.buildshield.inventory.shared.security.JwtTokenIssuer;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.InventoryIntegrationTest;
import pe.buildshield.inventory.support.TestDatabase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aislamiento multiempresa por HTTP con PostgreSQL real: una organización no ve ni toca el stock, las reservas
 * ni los movimientos de otra, aunque conozca sus identificadores (404 o listas vacías).
 */
@InventoryIntegrationTest
@AutoConfigureMockMvc
class StockIsolationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    ObjectMapper json;

    private TenantInfo andina;
    private TenantInfo pacifico;
    private UUID andinaWarehouse;
    private UUID andinaCement;

    @BeforeEach
    void twoOrganizations() {
        TestDatabase.reset(jdbc);
        andina = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "ADMINISTRATOR");
        pacifico = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "ADMINISTRATOR");
        andinaWarehouse = location(andina.organizationId(), "WAREHOUSE", "Almacén Andina");
        andinaCement = material(andina.organizationId(), "CEM-001");
        location(pacifico.organizationId(), "WAREHOUSE", "Almacén Pacífico");
    }

    @Test
    void each_organization_only_sees_and_loads_its_own_stock() throws Exception {
        perform(post("/api/v1/stock/entries").contentType(MediaType.APPLICATION_JSON).content(
                "{\"warehouseId\":\"" + andinaWarehouse + "\",\"materialId\":\"" + andinaCement + "\",\"quantity\":100}"),
                andina, 201);

        JsonNode own = json.readTree(perform(get("/api/v1/stock"), andina, 200));
        assertThat(own).hasSize(1);
        assertThat(own.get(0).path("warehouseName").asText()).isEqualTo("Almacén Andina");
        assertThat(own.get(0).path("availableQty").decimalValue()).isEqualByComparingTo("100");
        assertThat(json.readTree(perform(get("/api/v1/stock/movements"), andina, 200))).hasSize(1);

        assertThat(json.readTree(perform(get("/api/v1/stock"), pacifico, 200))).isEmpty();
        assertThat(json.readTree(perform(get("/api/v1/stock").param("warehouseId", andinaWarehouse.toString()),
                pacifico, 200))).isEmpty();
        assertThat(json.readTree(perform(get("/api/v1/stock/movements"), pacifico, 200))).isEmpty();
        assertThat(json.readTree(perform(get("/api/v1/stock/reservations"), pacifico, 200))).isEmpty();
        assertThat(json.readTree(perform(get("/api/v1/stock/worksites"), pacifico, 200))).isEmpty();
        perform(post("/api/v1/stock/entries").contentType(MediaType.APPLICATION_JSON).content(
                "{\"warehouseId\":\"" + andinaWarehouse + "\",\"materialId\":\"" + andinaCement + "\",\"quantity\":5}"),
                pacifico, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_items", Long.class)).isEqualTo(1);
    }

    @Test
    void anonymous_requests_and_wrong_roles_are_rejected() throws Exception {
        mvc.perform(get("/api/v1/stock")).andExpect(status().isUnauthorized());
        TenantInfo site = new TenantInfo(andina.organizationId(), UUID.randomUUID(), "SITE_MANAGER");
        perform(get("/api/v1/stock/reservations"), site, 403);
        perform(post("/api/v1/stock/entries").contentType(MediaType.APPLICATION_JSON).content(
                "{\"warehouseId\":\"" + andinaWarehouse + "\",\"materialId\":\"" + andinaCement + "\",\"quantity\":5}"),
                site, 403);
        perform(post("/api/v1/stock/entries").contentType(MediaType.APPLICATION_JSON).content(
                "{\"warehouseId\":\"" + andinaWarehouse + "\",\"materialId\":\"" + andinaCement + "\",\"quantity\":0}"),
                andina, 400);
    }

    private String perform(MockHttpServletRequestBuilder request, TenantInfo user, int expected) throws Exception {
        var response = mvc.perform(request.header("Authorization", "Bearer " + issuer.issue(user).value()))
                .andReturn().getResponse();
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(expected);
        return body;
    }

    private UUID location(UUID organizationId, String type, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO location_projection (location_id, organization_id, type, name, address, active, updated_at)
                VALUES (?, ?, ?, ?, 'Av. 1', true, ?)""", id, organizationId, type, name, Timestamp.from(Instant.now()));
        return id;
    }

    private UUID material(UUID organizationId, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO material_projection (material_id, organization_id, sku, name, unit, tolerance_percent, active,
                                                 updated_at)
                VALUES (?, ?, ?, 'Cemento', 'BAG', 2.5, true, ?)""", id, organizationId, sku,
                Timestamp.from(Instant.now()));
        return id;
    }
}
