package pe.buildshield.inventory.shared.error;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pe.buildshield.inventory.shared.tenant.MissingTenantContextException;

import java.util.List;

/**
 * Traduce toda excepción a {@link ErrorResponse}. Las excepciones estándar de Spring MVC conservan
 * su código HTTP; las de dominio se mapean a 400, 403, 404 y 409.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Object> handleValidation(ValidationException ex) {
        return domain(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Object> handleForbidden(ForbiddenException ex) {
        return domain(HttpStatus.FORBIDDEN, ex);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex) {
        return domain(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex) {
        return domain(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, ErrorResponse.of("CONCURRENT_MODIFICATION",
                "El recurso fue modificado por otra operación; vuelve a intentarlo"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, ErrorResponse.of(ForbiddenException.DEFAULT_CODE,
                "No tienes permiso para realizar esta operación"));
    }

    @ExceptionHandler(MissingTenantContextException.class)
    ResponseEntity<Object> handleMissingTenant(MissingTenantContextException ex) {
        return error(HttpStatus.FORBIDDEN, ErrorResponse.of("MISSING_ORGANIZATION",
                "La petición no está asociada a ninguna organización"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, new ErrorResponse(ValidationException.DEFAULT_CODE,
                "La petición tiene datos inválidos", details));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, new ErrorResponse("BAD_REQUEST", "La petición no es válida",
                List.of(new ErrorDetail(ex.getName(), "valor con formato incorrecto"))));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        // Sin organización, Hibernate no abre la sesión y Spring envuelve la causa
        // (por ejemplo, en CannotCreateTransactionException): se responde igual 403.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MissingTenantContextException missingTenant) {
                return handleMissingTenant(missingTenant);
            }
        }
        log.error("Error no controlado", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of("INTERNAL_ERROR", "Ocurrió un error inesperado"));
    }

    @ExceptionHandler({org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<Object> handleTemporaryFailure(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof MissingTenantContextException missingTenant) {
                return handleMissingTenant(missingTenant);
            }
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(ErrorResponse.of("TEMPORARILY_UNAVAILABLE",
                        "La operación no pudo completarse; reintenta con la misma clave"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, new ErrorResponse(ValidationException.DEFAULT_CODE,
                "La petición tiene datos inválidos", details));
    }

    /** Resto de excepciones estándar de Spring MVC: se conserva su código HTTP. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String code = status == null ? "HTTP_" + statusCode.value() : status.name();
        String message = status == HttpStatus.BAD_REQUEST
                ? "La petición no es válida"
                : status == null ? "Error HTTP " + statusCode.value() : status.getReasonPhrase();
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(code, message));
    }

    private static ResponseEntity<Object> domain(HttpStatus status, DomainException ex) {
        return error(status, new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    private static ResponseEntity<Object> error(HttpStatus status, ErrorResponse body) {
        return ResponseEntity.status(status).body(body);
    }
}
