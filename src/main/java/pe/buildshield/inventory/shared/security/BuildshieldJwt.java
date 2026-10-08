package pe.buildshield.inventory.shared.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;

/** Política de tokens de BuildShield: RS256, vigencia de 15 minutos y 30 s de tolerancia de reloj. */
public final class BuildshieldJwt {

    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private BuildshieldJwt() {
    }

    /** Decodificador que valida firma, emisor, vigencia y claims obligatorios. */
    public static JwtDecoder decoder(RSAPublicKey publicKey, String issuer, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new TokenPolicyValidator(TOKEN_LIFETIME, CLOCK_SKEW, clock)));
        return decoder;
    }
}
