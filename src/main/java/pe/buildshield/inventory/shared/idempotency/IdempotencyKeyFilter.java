package pe.buildshield.inventory.shared.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import pe.buildshield.inventory.shared.error.ErrorResponse;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Guarda negocio y respuesta juntos; los reintentos nunca repiten una operación confirmada. */
public class IdempotencyKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    public static final Duration RETENTION = Duration.ofHours(24);
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final IdempotencyStore store;
    private final TransactionTemplate transactionTemplate;
    private final ErrorResponseWriter errorWriter;
    private final Clock clock;
    private final List<String> requiredPaths;
    private final List<ReplayAuthorizer> authorizers;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public IdempotencyKeyFilter(IdempotencyStore store, TransactionTemplate transactionTemplate,
            ErrorResponseWriter errorWriter, Clock clock, List<String> requiredPaths) {
        this(store, transactionTemplate, errorWriter, clock, requiredPaths, List.of());
    }

    public IdempotencyKeyFilter(IdempotencyStore store, TransactionTemplate transactionTemplate,
            ErrorResponseWriter errorWriter, Clock clock, List<String> requiredPaths, List<ReplayAuthorizer> authorizers) {
        this.authorizers = List.copyOf(authorizers);
        this.store = store;
        this.transactionTemplate = transactionTemplate;
        this.errorWriter = errorWriter;
        this.clock = clock;
        this.requiredPaths = List.copyOf(requiredPaths);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UNSAFE_METHODS.contains(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            if (requiredPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path))) {
                error(response, 400, "IDEMPOTENCY_KEY_REQUIRED", "Esta operación requiere la cabecera " + HEADER);
            } else {
                chain.doFilter(request, response);
            }
            return;
        }
        UUID key;
        try {
            key = UUID.fromString(header.trim());
            if (!key.toString().equalsIgnoreCase(header.trim())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            error(response, 400, "INVALID_IDEMPOTENCY_KEY", "La cabecera " + HEADER + " debe ser un UUID");
            return;
        }
        BufferedRequest bufferedRequest = new BufferedRequest(request);
        if (bufferedRequest.body().length > BufferedRequest.MAX_BYTES) {
            error(response, 413, "REQUEST_TOO_LARGE", "La operación supera el límite de 1 MiB");
            return;
        }
        String fingerprint = RequestFingerprint.of(bufferedRequest);
        ContentCachingResponseWrapper bufferedResponse = new ContentCachingResponseWrapper(response);
        OperationContext.set(key);
        try {
            Outcome outcome = process(tenant.get(), key, bufferedRequest, path, fingerprint, bufferedResponse, chain);
            switch (outcome.kind()) {
                case IN_PROGRESS -> {
                    response.setHeader("Retry-After", "1");
                    error(response, 409, "IDEMPOTENCY_IN_PROGRESS", "La operación está en curso; reintenta con la misma clave");
                }
                case KEY_REUSED -> error(response, 400, "IDEMPOTENCY_KEY_REUSED", "La clave ya se usó con otra solicitud");
                case OWNER_MISMATCH -> error(response, 403, "IDEMPOTENCY_OWNER_MISMATCH", "La clave no corresponde a esta identidad");
                case EXPIRED -> error(response, 409, "IDEMPOTENCY_RESULT_EXPIRED", "La operación ya se confirmó; su respuesta venció");
                case FORBIDDEN -> error(response, 403, "FORBIDDEN", "Ya no tienes permiso para consultar esta operación");
                case UNVERIFIABLE -> error(response, 409, "IDEMPOTENCY_LEGACY_RECORD", "La operación ya existe y no puede reproducirse de forma segura");
                case REPLAY -> replay(response, outcome.record());
                case EXECUTED -> bufferedResponse.copyBodyToResponse();
            }
        } catch (TransactionException | TransientDataAccessException
                | org.springframework.dao.DataAccessResourceFailureException ex) {
            response.resetBuffer();
            response.setHeader("Retry-After", "1");
            error(response, 503, "TEMPORARILY_UNAVAILABLE", "La operación no pudo completarse; reintenta con la misma clave");
        } finally {
            OperationContext.clear();
        }
    }

    private Outcome process(TenantInfo tenant, UUID key, HttpServletRequest request, String path, String fingerprint,
            ContentCachingResponseWrapper response, FilterChain chain) throws ServletException, IOException {
        try {
            return transactionTemplate.execute(status -> {
                if (!store.tryLock(tenant.organizationId(), key)) return Outcome.of(Kind.IN_PROGRESS);
                Instant now = clock.instant();
                var existing = store.find(tenant.organizationId(), key);
                if (existing.isPresent()) {
                    IdempotencyRecord record = existing.get();
                    if (!record.isVerifiable()) return Outcome.of(Kind.UNVERIFIABLE);
                    if (!record.belongsTo(tenant.userId(), tenant.role())) return Outcome.of(Kind.OWNER_MISMATCH);
                    if (!record.isSameRequest(request.getMethod(), path, fingerprint)) return Outcome.of(Kind.KEY_REUSED);
                    if (record.responseExpired() || !record.createdAt().plus(RETENTION).isAfter(now))
                        return Outcome.of(Kind.EXPIRED);
                    if (authorizers.stream().filter(check -> check.supports(path))
                            .anyMatch(check -> !check.allowed(path, record.responseBody()))) return Outcome.of(Kind.FORBIDDEN);
                    return new Outcome(Kind.REPLAY, record);
                }
                invoke(chain, request, response);
                int code = response.getStatus();
                if (status.isRollbackOnly() || code < 200 || code >= 300) {
                    status.setRollbackOnly();
                } else {
                    store.save(new IdempotencyRecord(tenant.organizationId(), key, request.getMethod(), path, code,
                            response.getContentType(), response.getContentAsByteArray(), now, tenant.userId(),
                            tenant.role(), fingerprint, response.getHeader("Location"), false));
                }
                return Outcome.of(Kind.EXECUTED);
            });
        } catch (ChainException ex) {
            if (ex.getCause() instanceof ServletException cause) throw cause;
            throw (IOException) ex.getCause();
        }
    }

    private static void invoke(FilterChain chain, HttpServletRequest request, HttpServletResponse response) {
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException ex) { throw new ChainException(ex); }
    }
    private static void replay(HttpServletResponse response, IdempotencyRecord record) throws IOException {
        response.setStatus(record.responseStatus());
        response.setHeader(REPLAYED_HEADER, "true");
        if (record.responseContentType() != null) response.setContentType(record.responseContentType());
        if (record.responseLocation() != null) response.setHeader("Location", record.responseLocation());
        if (record.responseBody() != null) response.getOutputStream().write(record.responseBody());
    }
    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        errorWriter.write(response, status, ErrorResponse.of(code, message));
    }
    private enum Kind { IN_PROGRESS, KEY_REUSED, OWNER_MISMATCH, EXPIRED, UNVERIFIABLE, FORBIDDEN, REPLAY, EXECUTED }
    private record Outcome(Kind kind, IdempotencyRecord record) {
        static Outcome of(Kind kind) { return new Outcome(kind, null); }
    }
    private static final class ChainException extends RuntimeException {
        ChainException(Exception cause) { super(cause); }
    }
}
