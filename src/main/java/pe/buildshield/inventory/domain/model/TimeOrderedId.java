package pe.buildshield.inventory.domain.model;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identificadores UUID versión 7, crecientes dentro de cada instancia del servicio: milisegundos, un contador de 12
 * bits y azar. {@code stock_movements} no tiene una columna de secuencia, así que el {@code id} desempata los movimientos
 * del mismo instante (los de una transacción) en el orden en que se registraron.
 */
public final class TimeOrderedId {

    private static final AtomicLong LAST = new AtomicLong();

    private TimeOrderedId() {
    }

    public static UUID next() {
        long stamp = LAST.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis() << 12));
        long high = (stamp >>> 12) << 16 | 0x7000L | stamp & 0xFFFL;
        long low = ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
