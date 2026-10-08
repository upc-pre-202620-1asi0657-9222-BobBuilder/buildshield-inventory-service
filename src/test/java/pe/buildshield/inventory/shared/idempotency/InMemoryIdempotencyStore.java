package pe.buildshield.inventory.shared.idempotency;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store en memoria con la misma semántica que el de PostgreSQL: el bloqueo dura hasta el fin de la
 * transacción y lo guardado solo se ve tras el commit.
 */
class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(UUID organizationId, UUID key) {
        String id = id(organizationId, key);
        Object owner = new Object();
        if (locks.putIfAbsent(id, owner) != null) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                locks.remove(id, owner);
            }
        });
        return true;
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID organizationId, UUID key) {
        return Optional.ofNullable(records.get(id(organizationId, key)));
    }

    @Override
    public void save(IdempotencyRecord record) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                records.put(id(record.organizationId(), record.key()), record);
            }
        });
    }

    @Override
    public int retireResponsesCreatedBefore(Instant threshold) {
        List<String> expired = new ArrayList<>();
        records.forEach((id, record) -> {
            if (record.createdAt().isBefore(threshold) && !record.responseExpired()) {
                expired.add(id);
            }
        });
        expired.forEach(id -> records.computeIfPresent(id, (ignored, r) -> new IdempotencyRecord(
                r.organizationId(), r.key(), r.requestMethod(), r.requestPath(), r.responseStatus(),
                null, null, r.createdAt(), r.userId(), r.role(), r.requestFingerprint(), null, true)));
        return expired.size();
    }

    int size() {
        return records.size();
    }

    private static String id(UUID organizationId, UUID key) {
        return organizationId + "/" + key;
    }
}
