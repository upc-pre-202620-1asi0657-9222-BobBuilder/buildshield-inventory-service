package pe.buildshield.inventory.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.buildshield.inventory.shared.error.ErrorResponse;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Autentica la petición con el token Bearer: valida firma RS256, vigencia de 15 minutos, lista de
 * revocación y que el usuario siga activo con el rol del token, y carga el {@code SecurityContext} y el {@link TenantContext}. Ambos se limpian al
 * terminar la petición.
 *
 * <p>Sin token, la petición sigue sin autenticar y la cadena de seguridad decide. Con un token
 * inválido responde 401 directamente. Se registra dentro de la cadena de Spring Security:
 * {@code http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder decoder;
    private final RevokedTokenStore revokedTokens;
    private final ErrorResponseWriter errorWriter;

    public JwtAuthenticationFilter(JwtDecoder decoder, RevokedTokenStore revokedTokens, ErrorResponseWriter errorWriter) {
        this.decoder = decoder;
        this.revokedTokens = revokedTokens;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(header.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtException ex) {
            unauthorized(response, "INVALID_TOKEN", "El token es inválido o expiró");
            return;
        }
        try {
            if (revokedTokens.isRevoked(jwt.getId()) || revokedTokens.isUserBlocked(
                    UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(JwtClaimNames.ROLE))) {
                unauthorized(response, "TOKEN_REVOKED", "El token fue revocado");
                return;
            }
        } catch (org.springframework.dao.TransientDataAccessException
                | org.springframework.dao.DataAccessResourceFailureException
                | org.springframework.transaction.CannotCreateTransactionException ex) {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            response.setHeader("Retry-After", "1");
            errorWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ErrorResponse.of("TEMPORARILY_UNAVAILABLE", "No se pudo validar la sesión; reintenta con la misma clave"));
            return;
        }

        TenantInfo tenant = new TenantInfo(
                UUID.fromString(jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_ID)),
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtClaimNames.ROLE));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(tenant, jwt.getId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + tenant.role())));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        TenantContext.set(tenant);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorResponse.of(code, message));
    }
}
