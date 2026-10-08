package pe.buildshield.inventory.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.inventory.shared.tenant.MissingTenantContextException;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator())
            .build();

    @Test
    void validation_exception_is_400_with_details() throws Exception {
        mvc.perform(get("/fail/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDS_STOCK"))
                .andExpect(jsonPath("$.message").value("La cantidad supera el stock disponible"))
                .andExpect(jsonPath("$.details[0].field").value("quantity"))
                .andExpect(jsonPath("$.details[0].message").value("máximo 10"));
    }

    @Test
    void forbidden_exception_is_403() throws Exception {
        mvc.perform(get("/fail/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void not_found_exception_is_404() throws Exception {
        mvc.perform(get("/fail/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Pedido no encontrado"));
    }

    @Test
    void conflict_exception_is_409() throws Exception {
        mvc.perform(get("/fail/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_DISPATCHED"));
    }

    @Test
    void optimistic_lock_failure_is_409() throws Exception {
        mvc.perform(get("/fail/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void access_denied_is_403() throws Exception {
        mvc.perform(get("/fail/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missing_tenant_context_is_403() throws Exception {
        mvc.perform(get("/fail/no-tenant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_ORGANIZATION"));
    }

    @Test
    void wrapped_missing_tenant_context_is_403() throws Exception {
        mvc.perform(get("/fail/no-tenant-wrapped"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MISSING_ORGANIZATION"));
    }

    @Test
    void bean_validation_errors_are_400_with_one_detail_per_field() throws Exception {
        mvc.perform(post("/fail/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'quantity')]").exists());
    }

    @Test
    void unreadable_body_is_400() throws Exception {
        mvc.perform(post("/fail/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void type_mismatch_is_400() throws Exception {
        mvc.perform(get("/fail/orders/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unsupported_method_keeps_its_status_and_format() throws Exception {
        mvc.perform(post("/fail/forbidden"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unexpected_errors_are_500_without_internal_details() throws Exception {
        mvc.perform(get("/fail/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Ocurrió un error inesperado"));
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    record Body(@NotBlank String name, @Positive int quantity) {
    }

    @RestController
    static class FailingController {

        @GetMapping("/fail/validation")
        void validation() {
            throw new ValidationException("QUANTITY_EXCEEDS_STOCK", "La cantidad supera el stock disponible",
                    List.of(new ErrorDetail("quantity", "máximo 10")));
        }

        @GetMapping("/fail/forbidden")
        void forbidden() {
            throw new ForbiddenException("Solo un supervisor puede aprobar pedidos");
        }

        @GetMapping("/fail/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Pedido no encontrado");
        }

        @GetMapping("/fail/conflict")
        void conflict() {
            throw new ConflictException("ORDER_ALREADY_DISPATCHED", "El pedido ya fue despachado");
        }

        @GetMapping("/fail/optimistic-lock")
        void optimisticLock() {
            throw new OptimisticLockingFailureException("version mismatch");
        }

        @GetMapping("/fail/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/fail/no-tenant")
        void noTenant() {
            throw new MissingTenantContextException();
        }

        @GetMapping("/fail/no-tenant-wrapped")
        void noTenantWrapped() {
            throw new CannotCreateTransactionException("Could not open JPA EntityManager",
                    new IllegalStateException(new MissingTenantContextException()));
        }

        @PostMapping("/fail/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/fail/orders/{id}")
        void typed(@PathVariable long id) {
        }

        @GetMapping("/fail/unexpected")
        void unexpected() {
            throw new IllegalStateException("NullPointer en OrderRepositoryImpl línea 42");
        }
    }
}
