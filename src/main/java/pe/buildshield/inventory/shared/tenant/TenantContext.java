package pe.buildshield.inventory.shared.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Contexto multiempresa de la operación en curso (por hilo). Lo carga {@code JwtAuthenticationFilter}
 * en cada petición y lo usa la persistencia para filtrar por organización.
 *
 * <p>El modo sistema ({@link #runAsSystem}) es solo para procesos internos sin usuario, como el
 * la purga de claves de idempotencia: en ese modo no se filtra por organización.
 */
public final class TenantContext {

    /** Usuario con el que se auditan los cambios hechos en modo sistema. */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    private static final ThreadLocal<TenantInfo> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SYSTEM = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantInfo> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Devuelve el contexto o falla de forma segura si no hay organización. */
    public static TenantInfo require() {
        TenantInfo info = CURRENT.get();
        if (info == null) {
            throw new MissingTenantContextException();
        }
        return info;
    }

    public static boolean isSystem() {
        return Boolean.TRUE.equals(SYSTEM.get());
    }

    /** Fija el contexto de la petición. Quien lo fija debe llamar a {@link #clear()} al terminar. */
    public static void set(TenantInfo info) {
        CURRENT.set(info);
    }

    public static void clear() {
        CURRENT.remove();
        SYSTEM.remove();
    }

    public static void runAs(TenantInfo info, Runnable task) {
        callAs(info, () -> {
            task.run();
            return null;
        });
    }

    public static <T> T callAs(TenantInfo info, Supplier<T> task) {
        return with(info, false, task);
    }

    public static void runAsSystem(Runnable task) {
        callAsSystem(() -> {
            task.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> task) {
        return with(null, true, task);
    }

    private static <T> T with(TenantInfo info, boolean system, Supplier<T> task) {
        TenantInfo previousInfo = CURRENT.get();
        Boolean previousSystem = SYSTEM.get();
        CURRENT.set(info);
        SYSTEM.set(system);
        try {
            return task.get();
        } finally {
            restore(CURRENT, previousInfo);
            restore(SYSTEM, previousSystem);
        }
    }

    private static <V> void restore(ThreadLocal<V> holder, V previous) {
        if (previous == null) {
            holder.remove();
        } else {
            holder.set(previous);
        }
    }
}
