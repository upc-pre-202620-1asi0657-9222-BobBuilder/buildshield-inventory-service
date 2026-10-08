package pe.buildshield.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import pe.buildshield.inventory.support.InventoryIntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cada operación de la API está documentada por completo: resumen, descripción, parámetros descritos
 * y, en las respuestas exitosas con cuerpo, el esquema y un ejemplo.
 */
@InventoryIntegrationTest
@AutoConfigureMockMvc
class OpenApiCompletenessIT {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    /** 202 y 204 no devuelven cuerpo. */
    private static final Set<String> NO_BODY = Set.of("202", "204");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void every_operation_is_fully_documented() throws Exception {
        JsonNode spec = json.readTree(mvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> operations = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> entry : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(entry.getKey())) {
                    continue;
                }
                String name = entry.getKey().toUpperCase() + " " + path.getKey();
                operations.add(name);
                gaps.addAll(gapsOf(name, entry.getValue()));
            }
        }

        assertThat(operations).as("operaciones documentadas").hasSizeGreaterThanOrEqualTo(5);
        assertThat(gaps).as("documentación incompleta").isEmpty();
    }

    @Test
    void swagger_ui_html_leads_to_the_swagger_page() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("/swagger-ui/index.html")));
        String page = mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("swagger-ui");
        String config = mvc.perform(get("/api/v1/api-docs/swagger-config")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(config).path("url").asText()).isEqualTo("/api/v1/api-docs");
    }

    private static List<String> gapsOf(String name, JsonNode operation) {
        List<String> gaps = new ArrayList<>();
        if (operation.path("summary").asText().isBlank()) {
            gaps.add(name + ": sin resumen");
        }
        if (operation.path("description").asText().isBlank()) {
            gaps.add(name + ": sin descripción");
        }
        for (JsonNode parameter : operation.path("parameters")) {
            String parameterName = parameter.path("name").asText();
            if (parameter.path("description").asText().isBlank()) {
                gaps.add(name + ": parámetro " + parameterName + " sin descripción");
            }
            if (parameter.path("example").isMissingNode() && parameter.path("schema").path("example").isMissingNode()) {
                gaps.add(name + ": parámetro " + parameterName + " sin ejemplo");
            }
        }
        boolean hasSuccess = false;
        for (Map.Entry<String, JsonNode> response : operation.path("responses").properties()) {
            String code = response.getKey();
            if (!code.startsWith("2")) {
                continue;
            }
            hasSuccess = true;
            if (NO_BODY.contains(code)) {
                continue;
            }
            JsonNode media = response.getValue().path("content").path("application/json");
            if (media.isMissingNode()) {
                gaps.add(name + " " + code + ": sin contenido application/json");
                continue;
            }
            if (media.path("schema").isMissingNode()) {
                gaps.add(name + " " + code + ": sin esquema");
            }
            if (media.path("examples").isEmpty() && media.path("example").isMissingNode()) {
                gaps.add(name + " " + code + ": sin ejemplo de respuesta");
            }
        }
        if (!hasSuccess) {
            gaps.add(name + ": sin respuesta exitosa documentada");
        }
        return gaps;
    }
}
