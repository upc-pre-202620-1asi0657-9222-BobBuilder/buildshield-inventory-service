package pe.buildshield.inventory.shared.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import pe.buildshield.inventory.shared.tenant.TenantInfo;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Emite tokens RS256 de 15 minutos. Solo el servicio que autentica (buildshield-iam-service) tiene la clave privada. */
public class JwtTokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Clock clock;

    public JwtTokenIssuer(RSAPublicKey publicKey, RSAPrivateKey privateKey, String issuer, Clock clock) {
        RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        this.issuer = issuer;
        this.clock = clock;
    }

    public IssuedToken issue(TenantInfo tenant) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(BuildshieldJwt.TOKEN_LIFETIME);
        String tokenId = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(tenant.userId().toString())
                .id(tokenId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(JwtClaimNames.ORGANIZATION_ID, tenant.organizationId().toString())
                .claim(JwtClaimNames.ROLE, tenant.role())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, tokenId, expiresAt);
    }

    public record IssuedToken(String value, String tokenId, Instant expiresAt) {
    }
}
