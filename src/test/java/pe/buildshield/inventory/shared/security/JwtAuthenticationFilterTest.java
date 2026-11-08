package pe.buildshield.inventory.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import pe.buildshield.inventory.shared.error.ErrorResponseWriter;
import pe.buildshield.inventory.shared.tenant.TenantContext;
import pe.buildshield.inventory.shared.tenant.TenantInfo;
import pe.buildshield.inventory.shared.testsupport.MutableClock;
import pe.buildshield.inventory.shared.testsupport.TestKeys;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAuthenticationFilterTest {

    private static final String ISSUER = "buildshield-core";
    private static final TenantInfo TENANT = new TenantInfo(UUID.randomUUID(), UUID.randomUUID(), "SUPERVISOR");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
    private final Set<String> revoked = new HashSet<>();
    private final JwtDecoder decoder = BuildshieldJwt.decoder(TestKeys.publicKey(TestKeys.MAIN), ISSUER, clock);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(decoder, revoked::contains, new ErrorResponseWriter(new ObjectMapper()));
    private final JwtTokenIssuer issuer =
            new JwtTokenIssuer(TestKeys.publicKey(TestKeys.MAIN), TestKeys.privateKey(TestKeys.MAIN), ISSUER, clock);

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void unavailable_revocation_database_returns_503_without_authenticating() throws Exception {
        JwtAuthenticationFilter unavailable = new JwtAuthenticationFilter(decoder, id -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("credenciales privadas de la base");
        }, new ErrorResponseWriter(new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/stock/entries");
        request.addHeader("Authorization", "Bearer " + issuer.issue(TENANT).value());
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();
        unavailable.doFilter(request, response, (req, res) -> executed.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("TEMPORARILY_UNAVAILABLE").doesNotContain("credenciales privadas");
        assertThat(executed).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void valid_token_loads_security_and_tenant_context_during_the_request() throws Exception {
        String token = issuer.issue(TENANT).value();
        AtomicReference<TenantInfo> tenantInChain = new AtomicReference<>();
        AtomicReference<Authentication> authInChain = new AtomicReference<>();

        MockHttpServletResponse response = run(token, (req) -> {
            tenantInChain.set(TenantContext.require());
            authInChain.set(SecurityContextHolder.getContext().getAuthentication());
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenantInChain.get()).isEqualTo(TENANT);
        assertThat(authInChain.get().isAuthenticated()).isTrue();
        assertThat(authInChain.get().getName()).isEqualTo(TENANT.userId().toString());
        assertThat(authInChain.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPERVISOR");
        assertThat(((AuthenticatedUser) authInChain.get().getPrincipal()).tokenId()).isNotBlank();
    }

    @Test
    void contexts_are_cleared_after_the_request_even_if_it_fails() {
        String token = issuer.issue(TENANT).value();
        MockHttpServletRequest request = bearer(token);
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing)).hasMessage("boom");

        assertThat(TenantContext.current()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void request_without_token_continues_unauthenticated() throws Exception {
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            chainCalled.set(true);
            assertThat(TenantContext.current()).isEmpty();
        });

        assertThat(chainCalled.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void token_signed_with_another_key_is_rejected() throws Exception {
        String forged = sign(TestKeys.OTHER, claims(clock.instant(), BuildshieldJwt.TOKEN_LIFETIME).build());

        assertUnauthorized(run(forged, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void tampered_token_is_rejected() throws Exception {
        String token = issuer.issue(TENANT).value();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertUnauthorized(run(tampered, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_is_accepted_until_15_minutes_and_rejected_after() throws Exception {
        String token = issuer.issue(TENANT).value();

        clock.advance(Duration.ofMinutes(14));
        assertThat(run(token, req -> { }).getStatus()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(2));
        assertUnauthorized(run(token, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_with_a_lifetime_longer_than_15_minutes_is_rejected() throws Exception {
        String longLived = sign(TestKeys.MAIN, claims(clock.instant(), Duration.ofHours(8)).build());

        assertUnauthorized(run(longLived, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_issued_in_the_future_is_rejected() throws Exception {
        String future = sign(TestKeys.MAIN, claims(clock.instant().plus(Duration.ofMinutes(5)), Duration.ofMinutes(10)).build());

        assertUnauthorized(run(future, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_from_another_issuer_is_rejected() throws Exception {
        String other = sign(TestKeys.MAIN, claims(clock.instant(), BuildshieldJwt.TOKEN_LIFETIME).issuer("evil").build());

        assertUnauthorized(run(other, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_without_organization_is_rejected() throws Exception {
        JwtClaimsSet.Builder builder = claims(clock.instant(), BuildshieldJwt.TOKEN_LIFETIME);
        builder.claims(c -> c.remove(JwtClaimNames.ORGANIZATION_ID));

        assertUnauthorized(run(sign(TestKeys.MAIN, builder.build()), req -> { }), "INVALID_TOKEN");
    }

    @Test
    void token_with_non_uuid_subject_is_rejected() throws Exception {
        String token = sign(TestKeys.MAIN, claims(clock.instant(), BuildshieldJwt.TOKEN_LIFETIME).subject("admin").build());

        assertUnauthorized(run(token, req -> { }), "INVALID_TOKEN");
    }

    @Test
    void revoked_token_is_rejected() throws Exception {
        JwtTokenIssuer.IssuedToken token = issuer.issue(TENANT);
        revoked.add(token.tokenId());

        assertUnauthorized(run(token.value(), req -> { }), "TOKEN_REVOKED");
    }

    @Test
    void issued_token_expires_in_15_minutes() {
        JwtTokenIssuer.IssuedToken token = issuer.issue(TENANT);

        assertThat(token.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(decoder.decode(token.value()).getClaimAsString(JwtClaimNames.ROLE)).isEqualTo("SUPERVISOR");
    }

    @Test
    void pem_keys_round_trip() {
        assertThat(PemKeys.publicKey(TestKeys.publicPem(TestKeys.MAIN))).isEqualTo(TestKeys.MAIN.getPublic());
        assertThat(PemKeys.privateKey(TestKeys.privatePem(TestKeys.MAIN))).isEqualTo(TestKeys.MAIN.getPrivate());
        assertThatThrownBy(() -> PemKeys.publicKey("no es una clave")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PemKeys.privateKey("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletResponse run(String token, Consumer<MockHttpServletRequest> inChain) throws Exception {
        MockHttpServletRequest request = bearer(token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> inChain.accept((MockHttpServletRequest) req));
        return response;
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static void assertUnauthorized(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
        assertThat(TenantContext.current()).isEmpty();
    }

    private static JwtClaimsSet.Builder claims(Instant issuedAt, Duration lifetime) {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(TENANT.userId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime))
                .claim(JwtClaimNames.ORGANIZATION_ID, TENANT.organizationId().toString())
                .claim(JwtClaimNames.ROLE, TENANT.role());
    }

    private static String sign(KeyPair keys, JwtClaimsSet claims) {
        RSAKey key = new RSAKey.Builder(TestKeys.publicKey(keys)).privateKey(TestKeys.privateKey(keys)).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
