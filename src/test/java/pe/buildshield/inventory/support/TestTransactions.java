package pe.buildshield.inventory.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/** Transacciones sin base de datos para pruebas unitarias de servicios: cuentan commits y rollbacks. */
public class TestTransactions extends AbstractPlatformTransactionManager {

    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    public TransactionTemplate template() {
        return new TransactionTemplate(this);
    }

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
