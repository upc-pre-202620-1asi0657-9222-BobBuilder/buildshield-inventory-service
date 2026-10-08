package pe.buildshield.inventory.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import pe.buildshield.inventory.shared.correlation.CorrelationIdFilter;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;
import pe.buildshield.inventory.shared.error.GlobalExceptionHandler;
import pe.buildshield.inventory.shared.idempotency.IdempotencyKeyFilter;
import pe.buildshield.inventory.shared.idempotency.IdempotencyKeyPurger;
import pe.buildshield.inventory.shared.idempotency.JdbcIdempotencyStore;
import pe.buildshield.inventory.shared.persistence.OrganizationTenantResolver;
import pe.buildshield.inventory.shared.security.JwtAuthenticationFilter;
import pe.buildshield.inventory.shared.security.JwtTokenIssuer;
import pe.buildshield.inventory.shared.security.RevokedTokenStore;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.shared.testsupport.TestKeys;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuildshieldAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BuildshieldClockAutoConfiguration.class,
                    BuildshieldWebAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void registers_correlation_filter_first_and_the_error_format() {
        web.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class).hasSingleBean(ErrorResponseWriter.class);
            FilterRegistrationBean<?> correlation = registration(context.getBeansOfType(FilterRegistrationBean.class),
                    CorrelationIdFilter.class);
            assertThat(correlation.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(context).hasSingleBean(Clock.class);
        });
    }

    @Test
    void without_public_key_there_is_no_jwt_filter() {
        web.run(context -> assertThat(context).doesNotHaveBean(JwtAuthenticationFilter.class)
                .doesNotHaveBean(JwtDecoder.class));
    }

    @Test
    void with_public_key_and_revocation_store_the_jwt_filter_is_ready_for_the_security_chain() {
        web.withPropertyValues(
                        "buildshield.security.issuer=buildshield-core",
                        "buildshield.security.jwt-public-key=" + TestKeys.publicPem(TestKeys.MAIN),
                        "buildshield.security.jwt-private-key=" + TestKeys.privatePem(TestKeys.MAIN))
                .withBean(RevokedTokenStore.class, () -> tokenId -> false)
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtAuthenticationFilter.class).hasSingleBean(JwtTokenIssuer.class);
                    FilterRegistrationBean<?> jwt = registration(context.getBeansOfType(FilterRegistrationBean.class),
                            JwtAuthenticationFilter.class);
                    assertThat(jwt.isEnabled()).isFalse();

                    TenantInfo tenant = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "SUPERVISOR");
                    String token = context.getBean(JwtTokenIssuer.class).issue(tenant).value();
                    assertThat(context.getBean(JwtDecoder.class).decode(token).getSubject())
                            .isEqualTo(tenant.userId().toString());
                });
    }

    @Test
    void public_key_without_revocation_store_fails_at_startup() {
        web.withPropertyValues("buildshield.security.jwt-public-key=" + TestKeys.publicPem(TestKeys.MAIN))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("RevokedTokenStore"));
    }

    @Test
    void idempotency_filter_runs_after_spring_security_with_the_configured_paths() {
        web.withPropertyValues("buildshield.idempotency.required-paths=/api/v1/receptions/**")
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcIdempotencyStore.class).hasSingleBean(IdempotencyKeyPurger.class);
                    FilterRegistrationBean<?> idempotency = registration(
                            context.getBeansOfType(FilterRegistrationBean.class), IdempotencyKeyFilter.class);
                    assertThat(idempotency.getOrder()).isEqualTo(BuildshieldWebAutoConfiguration.IDEMPOTENCY_FILTER_ORDER)
                            .isGreaterThan(-100);
                    assertThat(context.getBean(BuildshieldProperties.class).getIdempotency().getRequiredPaths())
                            .containsExactly("/api/v1/receptions/**");
                });
    }

    @Test
    void idempotency_can_be_disabled() {
        web.withPropertyValues("buildshield.idempotency.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyKeyPurger.class));
    }

    @Test
    void persistence_registers_the_tenant_resolver_in_hibernate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BuildshieldPersistenceAutoConfiguration.class))
                .withBean(Clock.class, Clock::systemUTC)
                .run(context -> {
                    Map<String, Object> hibernateProperties = new HashMap<>();
                    context.getBean(HibernatePropertiesCustomizer.class).customize(hibernateProperties);
                    assertThat(hibernateProperties.get(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER))
                            .isSameAs(context.getBean(OrganizationTenantResolver.class));
                    assertThat(hibernateProperties.get(AvailableSettings.STATEMENT_INSPECTOR))
                            .isInstanceOf(pe.buildshield.inventory.shared.persistence.TenantGuardStatementInspector.class);
                });
    }

    @Test
    void jpa_auditing_is_skipped_without_an_entity_manager_factory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BuildshieldClockAutoConfiguration.class,
                        BuildshieldJpaAuditingAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(pe.buildshield.inventory.shared.persistence.TenantAuditorAware.class));
    }

    private static FilterRegistrationBean<?> registration(Map<String, FilterRegistrationBean> registrations,
            Class<?> filterType) {
        return registrations.values().stream()
                .filter(registration -> filterType.isInstance(registration.getFilter()))
                .findFirst().orElseThrow();
    }
}
