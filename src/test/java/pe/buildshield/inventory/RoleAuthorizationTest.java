package pe.buildshield.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pe.buildshield.inventory.application.StockCommandService;
import pe.buildshield.inventory.application.StockCountCommandService;
import pe.buildshield.inventory.application.StockCountQueryService;
import pe.buildshield.inventory.application.StockLevel;
import pe.buildshield.inventory.application.StockQueryService;
import pe.buildshield.inventory.shared.security.JwtTokenIssuer;
import pe.buildshield.inventory.shared.security.RevokedTokenStore;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.support.WebSliceTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de autorización de toda la API de inventory: cada endpoint contra usuario anónimo y cada rol (los
 * servicios están simulados). Además verifica que ningún endpoint quede sin una regla explícita de
 * {@code @PreAuthorize}. Portada de la matriz del Core (filas de stock).
 */
@WebMvcTest
@WebSliceTest
class RoleAuthorizationTest {

    private static final String ID = "00000000-0000-0000-0000-0000000000cc";
    private static final Map<String, String> BODIES = Map.of(
            "entry", "{\"warehouseId\":\"00000000-0000-0000-0000-0000000000aa\","
                    + "\"materialId\":\"00000000-0000-0000-0000-0000000000bb\",\"quantity\":10}",
            "count", "{\"warehouseId\":\"00000000-0000-0000-0000-0000000000aa\"}",
            "lines", "{\"lines\":[{\"materialId\":\"00000000-0000-0000-0000-0000000000bb\",\"countedQty\":5}]}",
            "note", "{\"note\":\"Recontado\"}",
            "reason", "{\"reason\":\"Rotura\"}");

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtTokenIssuer issuer;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    RevokedTokenStore revokedTokens;

    @MockitoBean
    StockCommandService commands;

    @MockitoBean
    StockQueryService queries;

    @MockitoBean
    StockCountCommandService countCommands;

    @MockitoBean
    StockCountQueryService countQueries;

    @BeforeEach
    void stubServices() {
        when(commands.registerEntry(any(), any(), any(), any(), any())).thenReturn(new StockLevel(UUID.randomUUID(),
                UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ZERO));
        when(queries.list(any())).thenReturn(List.of());
        when(queries.listWorksites(any())).thenReturn(List.of());
        when(queries.reservations(any(), any())).thenReturn(List.of());
        when(queries.movements(any(), any())).thenReturn(List.of());
        // Conteos: los mocks devuelven null o listas vacías; solo importa el estado HTTP.
    }

    @ParameterizedTest(name = "{0} {1} como {3} -> {4}")
    @CsvSource({
            // método, ruta, cuerpo, quién, estado
            "POST , /api/v1/stock/entries, entry, ANONYMOUS, 401",
            "POST , /api/v1/stock/entries, entry, ADMINISTRATOR, 201",
            "POST , /api/v1/stock/entries, entry, WAREHOUSE_MANAGER, 201",
            "POST , /api/v1/stock/entries, entry, SITE_MANAGER, 403",
            "GET  , /api/v1/stock, , ANONYMOUS, 401",
            "GET  , /api/v1/stock, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock, , SITE_MANAGER, 200",
            "GET  , /api/v1/stock/worksites, , ANONYMOUS, 401",
            "GET  , /api/v1/stock/worksites, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock/worksites, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock/worksites, , SITE_MANAGER, 200",
            "GET  , /api/v1/stock/reservations, , ANONYMOUS, 401",
            "GET  , /api/v1/stock/reservations, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock/reservations, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock/reservations, , SITE_MANAGER, 403",
            "GET  , /api/v1/stock/movements, , ANONYMOUS, 401",
            "GET  , /api/v1/stock/movements, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock/movements, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock/movements, , SITE_MANAGER, 200",
            "GET  , /api/v1/stock/movements/history, , ANONYMOUS, 401",
            "GET  , /api/v1/stock/movements/history, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock/movements/history, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock/movements/history, , SITE_MANAGER, 200",
            // Conteos de validación (US41): administrador y encargado de almacén
            "POST , /api/v1/stock/counts, count, ANONYMOUS, 401",
            "POST , /api/v1/stock/counts, count, ADMINISTRATOR, 201",
            "POST , /api/v1/stock/counts, count, WAREHOUSE_MANAGER, 201",
            "POST , /api/v1/stock/counts, count, SITE_MANAGER, 403",
            "GET  , /api/v1/stock/counts, , ANONYMOUS, 401",
            "GET  , /api/v1/stock/counts, , WAREHOUSE_MANAGER, 200",
            "GET  , /api/v1/stock/counts, , SITE_MANAGER, 403",
            "GET  , /api/v1/stock/counts/{id}, , ADMINISTRATOR, 200",
            "GET  , /api/v1/stock/counts/{id}, , SITE_MANAGER, 403",
            "PUT  , /api/v1/stock/counts/{id}/lines, lines, WAREHOUSE_MANAGER, 200",
            "PUT  , /api/v1/stock/counts/{id}/lines, lines, SITE_MANAGER, 403",
            "POST , /api/v1/stock/counts/{id}/finish, , ADMINISTRATOR, 200",
            "POST , /api/v1/stock/counts/{id}/finish, , SITE_MANAGER, 403",
            "POST , /api/v1/stock/counts/{id}/review, note, WAREHOUSE_MANAGER, 200",
            "POST , /api/v1/stock/counts/{id}/review, note, SITE_MANAGER, 403",
            "POST , /api/v1/stock/counts/{id}/adjustment, reason, ADMINISTRATOR, 200",
            "POST , /api/v1/stock/counts/{id}/adjustment, reason, WAREHOUSE_MANAGER, 200",
            "POST , /api/v1/stock/counts/{id}/adjustment, reason, SITE_MANAGER, 403",
            "POST , /api/v1/stock/counts/{id}/adjustment, reason, ANONYMOUS, 401",
            "POST , /api/v1/stock/counts/{id}/cancel, , WAREHOUSE_MANAGER, 200",
            "POST , /api/v1/stock/counts/{id}/cancel, , SITE_MANAGER, 403",
    })
    void endpoint_is_allowed_only_for_its_roles(String method, String path, String body, String who, int expectedStatus)
            throws Exception {
        String uri = path.replace("{id}", ID);
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(uri);
            case "PUT" -> put(uri);
            default -> post(uri);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(BODIES.get(body));
        }
        if (!"ANONYMOUS".equals(who)) {
            String token = issuer.issue(new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), who)).value();
            request.header("Authorization", "Bearer " + token);
        }

        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    @Test
    void every_endpoint_declares_its_authorization_rule() {
        List<String> withoutRule = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("pe.buildshield.inventory"))
                .filter(handler -> !hasPreAuthorize(handler))
                .map(handler -> handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName())
                .toList();

        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
        assertThat(withoutRule).as("endpoints sin @PreAuthorize").isEmpty();
    }

    private static boolean hasPreAuthorize(HandlerMethod handler) {
        return handler.hasMethodAnnotation(PreAuthorize.class)
                || handler.getBeanType().isAnnotationPresent(PreAuthorize.class);
    }
}
