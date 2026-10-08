package pe.buildshield.inventory.shared.idempotency;

import java.util.Optional;
import java.util.UUID;

/** Identificador técnico de la operación HTTP, disponible durante su transacción. */
public final class OperationContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private OperationContext() { }
    public static Optional<UUID> current() { return Optional.ofNullable(CURRENT.get()); }
    static void set(UUID key) { CURRENT.set(key); }
    static void clear() { CURRENT.remove(); }
}
