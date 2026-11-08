package pe.buildshield.inventory.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pe.buildshield.inventory.shared.correlation.CorrelationIdFilter;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;
import pe.buildshield.inventory.shared.error.GlobalExceptionHandler;
import pe.buildshield.inventory.shared.idempotency.IdempotencyKeyFilter;
import pe.buildshield.inventory.shared.idempotency.IdempotencyKeyPurger;
import pe.buildshield.inventory.shared.idempotency.IdempotencyStore;
import pe.buildshield.inventory.shared.idempotency.JdbcIdempotencyStore;
import pe.buildshield.inventory.shared.security.BuildshieldJwt;
import pe.buildshield.inventory.shared.security.JwtAuthenticationFilter;
import pe.buildshield.inventory.shared.security.JwtTokenIssuer;
import pe.buildshield.inventory.shared.security.PemKeys;
import pe.buildshield.inventory.shared.security.RevokedTokenStore;

import java.time.Clock;

/**
 * Componentes web: correlación, formato de error, autenticación JWT e idempotencia.
 *
 * <p>Orden de los filtros: correlación (primero) → cadena de Spring Security, donde la unidad agrega
 * {@link JwtAuthenticationFilter} → idempotencia (necesita la organización del token).
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, BuildshieldClockAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(BuildshieldProperties.class)
public class BuildshieldWebAutoConfiguration {

    /** Justo después de la cadena de Spring Security. */
    public static final int IDEMPOTENCY_FILTER_ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 2;

    @Bean
    @ConditionalOnMissingBean
    ErrorResponseWriter errorResponseWriter(ObjectMapper objectMapper) {
        return new ErrorResponseWriter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "buildshield.security", name = "jwt-public-key")
    static class JwtConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JwtDecoder buildshieldJwtDecoder(BuildshieldProperties properties, Clock clock) {
            BuildshieldProperties.Security security = properties.getSecurity();
            return BuildshieldJwt.decoder(PemKeys.publicKey(security.getJwtPublicKey()), security.getIssuer(), clock);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtDecoder decoder, ObjectProvider<RevokedTokenStore> revokedTokens,
                ErrorResponseWriter errorWriter) {
            RevokedTokenStore store = revokedTokens.getIfAvailable(() -> {
                throw new IllegalStateException(
                        "Falta un bean RevokedTokenStore para validar tokens");
            });
            return new JwtAuthenticationFilter(decoder, store, errorWriter);
        }

        /** El filtro JWT va dentro de la cadena de Spring Security, no como filtro de servlet suelto. */
        @Bean
        FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(JwtAuthenticationFilter filter) {
            FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "buildshield.security", name = "jwt-private-key")
        JwtTokenIssuer jwtTokenIssuer(BuildshieldProperties properties, Clock clock) {
            BuildshieldProperties.Security security = properties.getSecurity();
            return new JwtTokenIssuer(PemKeys.publicKey(security.getJwtPublicKey()),
                    PemKeys.privateKey(security.getJwtPrivateKey()), security.getIssuer(), clock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "buildshield.idempotency", name = "enabled", matchIfMissing = true)
    static class IdempotencyConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyStore idempotencyStore(JdbcTemplate jdbcTemplate) {
            return new JdbcIdempotencyStore(jdbcTemplate);
        }

        @Bean
        FilterRegistrationBean<IdempotencyKeyFilter> idempotencyKeyFilter(IdempotencyStore store,
                PlatformTransactionManager transactionManager, ErrorResponseWriter errorWriter, Clock clock,
                BuildshieldProperties properties, ObjectProvider<pe.buildshield.inventory.shared.idempotency.ReplayAuthorizer> authorizers) {
            IdempotencyKeyFilter filter = new IdempotencyKeyFilter(store, new TransactionTemplate(transactionManager),
                    errorWriter, clock, properties.getIdempotency().getRequiredPaths(), authorizers.orderedStream().toList());
            FilterRegistrationBean<IdempotencyKeyFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(IDEMPOTENCY_FILTER_ORDER);
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        IdempotencyKeyPurger idempotencyKeyPurger(IdempotencyStore store, PlatformTransactionManager transactionManager,
                Clock clock) {
            return new IdempotencyKeyPurger(store, new TransactionTemplate(transactionManager), clock);
        }
    }
}
