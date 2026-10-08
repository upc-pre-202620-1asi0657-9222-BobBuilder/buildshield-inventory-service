package pe.buildshield.inventory.support;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.core.env.Environment;

/**
 * Opt-in explícito a una base temporal externa: nunca acepta una base de trabajo. Se activa con
 * {@code -Dbuildshield.test.external-database=true}; por defecto usa {@code jdbc:postgresql://127.0.0.1:5433/<servicio>_it}
 * con el usuario {@code buildshield_core}, y se cambian con {@code -Dbuildshield.test.<servicio>-url=...}
 * y {@code -Dbuildshield.test.database-user=...}.
 */
public final class ExternalPostgres {

    private ExternalPostgres() {
    }

    public static JdbcConnectionDetails connection(Environment env, String service) {
        String url = env.getProperty("buildshield.test." + service + "-url",
                "jdbc:postgresql://127.0.0.1:5433/" + service + "_it");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/" + service + "_it")) {
            throw new IllegalArgumentException("Se requiere una base temporal local " + service + "_it");
        }
        String user = env.getProperty("buildshield.test.database-user", "buildshield_core");
        String password = env.getProperty("buildshield.test.database-password", "");
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return url;
            }

            @Override
            public String getUsername() {
                return user;
            }

            @Override
            public String getPassword() {
                return password;
            }

            @Override
            public String getDriverClassName() {
                return "org.postgresql.Driver";
            }
        };
    }
}
