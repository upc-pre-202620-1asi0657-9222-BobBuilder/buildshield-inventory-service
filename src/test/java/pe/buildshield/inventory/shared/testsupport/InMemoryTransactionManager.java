package pe.buildshield.inventory.shared.testsupport;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

/** Gestor de transacciones sin base de datos: solo activa la sincronización y cuenta commits y rollbacks. */
public class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks.incrementAndGet();
    }
}
