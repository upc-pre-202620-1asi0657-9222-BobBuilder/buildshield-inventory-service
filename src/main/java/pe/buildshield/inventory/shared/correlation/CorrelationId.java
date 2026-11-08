package pe.buildshield.inventory.shared.correlation;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identificador de correlación de la operación en curso. Vive en el MDC de SLF4J, de modo que
 * aparece en los logs y se copia a los eventos de integración.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /**
     * Ejecuta la tarea con el identificador dado (o uno nuevo si es nulo o inválido) y restaura
     * el valor anterior al terminar.
     */
    public static void runWith(String correlationId, Runnable task) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, isValid(correlationId) ? correlationId : newId());
        try {
            task.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
