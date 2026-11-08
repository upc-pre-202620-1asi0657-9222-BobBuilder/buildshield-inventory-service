package pe.buildshield.inventory.shared.security;

/** Claims propios de los tokens de BuildShield, además de sub, jti, iat, exp e iss. */
public final class JwtClaimNames {

    public static final String ORGANIZATION_ID = "org";
    public static final String ROLE = "role";

    private JwtClaimNames() {
    }
}
