package pe.buildshield.inventory.shared.testsupport;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Pares de claves RSA generados para las pruebas. */
public final class TestKeys {

    public static final KeyPair MAIN = generate();
    public static final KeyPair OTHER = generate();

    private TestKeys() {
    }

    public static RSAPublicKey publicKey(KeyPair pair) {
        return (RSAPublicKey) pair.getPublic();
    }

    public static RSAPrivateKey privateKey(KeyPair pair) {
        return (RSAPrivateKey) pair.getPrivate();
    }

    public static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    public static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
