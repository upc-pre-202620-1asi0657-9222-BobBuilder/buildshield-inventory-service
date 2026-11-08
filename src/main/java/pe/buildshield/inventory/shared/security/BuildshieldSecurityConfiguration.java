package pe.buildshield.inventory.shared.security;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import pe.buildshield.inventory.shared.config.BuildshieldProperties;
import pe.buildshield.inventory.shared.error.ErrorResponse;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Seguridad del servicio: API sin sesión de servidor, autenticada con el JWT del kernel compartido.
 * Cada servicio vuelve a validar el token aunque ya lo haya validado el gateway (defensa en
 * profundidad, QAS04).
 *
 * <p>Dos cadenas:
 * <ol>
 *   <li>Endpoints públicos (salud, documentación y los de {@code buildshield.security.public-endpoints}):
 *       <b>sin</b> filtro JWT. Un token vencido o revocado que el cliente envíe por costumbre no debe
 *       impedir renovar la sesión ni iniciarla de nuevo.</li>
 *   <li>Todo lo demás: exige un JWT válido. La autorización por rol está en cada endpoint con
 *       {@code @PreAuthorize}.</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class BuildshieldSecurityConfiguration {

    static final String[] API_DOCS = {"/api/v1/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    @Order(1)
    SecurityFilterChain publicEndpoints(HttpSecurity http, BuildshieldProperties properties) throws Exception {
        return stateless(http)
                .securityMatcher(publicRequests(properties.getSecurity().getPublicEndpoints()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain authenticatedApi(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
            ErrorResponseWriter errorWriter) throws Exception {
        return stateless(http)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> errorWriter.write(response,
                                HttpStatus.UNAUTHORIZED.value(),
                                ErrorResponse.of("UNAUTHENTICATED", "Debes iniciar sesión para usar este recurso")))
                        .accessDeniedHandler((request, response, ex) -> errorWriter.write(response,
                                HttpStatus.FORBIDDEN.value(),
                                ErrorResponse.of("FORBIDDEN", "No tienes permiso para realizar esta operación"))))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    /** @param endpoints {@code "POST /ruta"} para un método o {@code "/ruta"} para cualquiera */
    static RequestMatcher publicRequests(List<String> endpoints) {
        List<RequestMatcher> matchers = new ArrayList<>();
        matchers.add(EndpointRequest.to(HealthEndpoint.class));
        for (String endpoint : endpoints) {
            String[] parts = endpoint.trim().split("\s+", 2);
            if (parts.length == 2) {
                matchers.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.valueOf(parts[0]), parts[1]));
            } else {
                matchers.add(PathPatternRequestMatcher.withDefaults().matcher(parts[0]));
            }
        }
        for (String path : API_DOCS) {
            matchers.add(PathPatternRequestMatcher.withDefaults().matcher(path));
        }
        return new OrRequestMatcher(matchers);
    }
}
