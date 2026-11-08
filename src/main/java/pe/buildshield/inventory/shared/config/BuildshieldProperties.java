package pe.buildshield.inventory.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Configuración del kernel compartido ({@code buildshield.*}). */
@ConfigurationProperties(prefix = "buildshield")
public class BuildshieldProperties {

    private final Security security = new Security();
    private final Idempotency idempotency = new Idempotency();
    private final Messaging messaging = new Messaging();

    public Security getSecurity() {
        return security;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public Messaging getMessaging() {
        return messaging;
    }

    public static class Security {

        /** Emisor esperado (claim iss). */
        private String issuer = "buildshield";

        /** Clave pública RSA en PEM para validar tokens. Llega por variable de entorno. */
        private String jwtPublicKey;

        /** Clave privada RSA en PEM para emitir tokens. Solo en la unidad que autentica. */
        private String jwtPrivateKey;

        /**
         * Endpoints públicos (sin filtro JWT) además de salud y documentación, como
         * {@code "POST /api/v1/auth/sign-in"} o solo la ruta para cualquier método.
         */
        private List<String> publicEndpoints = new ArrayList<>();

        public List<String> getPublicEndpoints() {
            return publicEndpoints;
        }

        public void setPublicEndpoints(List<String> publicEndpoints) {
            this.publicEndpoints = publicEndpoints;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwtPublicKey() {
            return jwtPublicKey;
        }

        public void setJwtPublicKey(String jwtPublicKey) {
            this.jwtPublicKey = jwtPublicKey;
        }

        public String getJwtPrivateKey() {
            return jwtPrivateKey;
        }

        public void setJwtPrivateKey(String jwtPrivateKey) {
            this.jwtPrivateKey = jwtPrivateKey;
        }
    }

    public static class Idempotency {

        /** Rutas (patrón Ant) en las que la cabecera Idempotency-Key es obligatoria. */
        private List<String> requiredPaths = new ArrayList<>();

        public List<String> getRequiredPaths() {
            return requiredPaths;
        }

        public void setRequiredPaths(List<String> requiredPaths) {
            this.requiredPaths = requiredPaths;
        }
    }

    /** Eventos de integración: outbox y tópico propio del servicio. */
    public static class Messaging {

        /** Tópico en el que publica este servicio (uno por contexto, por ejemplo buildshield.iam). */
        private String topic;

        /** Particiones del tópico propio si el servicio lo crea. */
        private int partitions = 3;

        /** Eventos del outbox enviados en cada pasada del publicador. */
        private int batchSize = 100;

        /** Espera máxima de la confirmación de Kafka por evento. */
        private java.time.Duration sendTimeout = java.time.Duration.ofSeconds(10);

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public java.time.Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(java.time.Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }
}
