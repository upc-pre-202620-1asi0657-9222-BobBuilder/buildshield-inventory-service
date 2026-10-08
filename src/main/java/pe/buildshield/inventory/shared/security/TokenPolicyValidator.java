package pe.buildshield.inventory.shared.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reglas propias de BuildShield sobre el token: claims obligatorios, vigencia máxima de 15 minutos
 * ({@code exp - iat}) y emisión no futura.
 */
class TokenPolicyValidator implements OAuth2TokenValidator<Jwt> {

    private static final List<String> REQUIRED_CLAIMS = List.of(
            "sub", "jti", "iat", "exp", JwtClaimNames.ORGANIZATION_ID, JwtClaimNames.ROLE);

    private final Duration maxLifetime;
    private final Duration clockSkew;
    private final Clock clock;

    TokenPolicyValidator(Duration maxLifetime, Duration clockSkew, Clock clock) {
        this.maxLifetime = maxLifetime;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        for (String claim : REQUIRED_CLAIMS) {
            if (!jwt.hasClaim(claim)) {
                return failure("Falta el claim obligatorio '" + claim + "'");
            }
        }
        if (!isUuid(jwt.getSubject()) || !isUuid(jwt.getClaimAsString(JwtClaimNames.ORGANIZATION_ID))) {
            return failure("Los claims 'sub' y 'org' deben ser UUID");
        }
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (Duration.between(issuedAt, expiresAt).compareTo(maxLifetime) > 0) {
            return failure("La vigencia del token supera " + maxLifetime.toMinutes() + " minutos");
        }
        if (issuedAt.isAfter(clock.instant().plus(clockSkew))) {
            return failure("El token fue emitido en el futuro");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
