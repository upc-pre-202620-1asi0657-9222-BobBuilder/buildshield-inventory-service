package pe.buildshield.inventory.shared.messaging;

/** Un tópico por contexto que publica. Clave del mensaje: el {@code aggregateId}. */
public final class Topics {

    public static final String IAM = "buildshield.iam";
    public static final String ORGANIZATION = "buildshield.organization";
    public static final String INVENTORY = "buildshield.inventory";
    public static final String ORDERING = "buildshield.ordering";
    public static final String DISCREPANCY = "buildshield.discrepancy";
    public static final String SUBSCRIPTION = "buildshield.subscription";

    private Topics() {
    }
}
