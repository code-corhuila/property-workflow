package co.edu.corhuila.property.workflow.adapter.out.persistence;

import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Only with PERSISTENCE=memory, for tests and local development: never a default
 * (norm 5.5.2). Same contract as the JDBC adapter; every reader gets its own copy, as
 * it would from a database.
 */
public class InMemorySagaStore implements SagaStore {

    private final Map<UUID, SagaInstance> sagas = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();

    @Override
    public synchronized CreateResult create(SagaInstance saga) {
        UUID first = keys.get(saga.idempotencyKey());
        if (first != null) {
            return new CreateResult(copy(sagas.get(first)), false);
        }
        sagas.put(saga.id(), copy(saga));
        keys.put(saga.idempotencyKey(), saga.id());
        return new CreateResult(saga, true);
    }

    @Override
    public synchronized void save(SagaInstance saga) {
        sagas.put(saga.id(), copy(saga));
    }

    @Override
    public synchronized Optional<SagaInstance> get(UUID id) {
        return Optional.ofNullable(sagas.get(id)).map(InMemorySagaStore::copy);
    }

    @Override
    public synchronized List<SagaInstance> findStale(Instant updatedBefore, int limit) {
        return sagas.values().stream()
                .filter(s -> s.status() == SagaStatus.RUNNING && s.updatedAt().isBefore(updatedBefore))
                .sorted(Comparator.comparing(SagaInstance::updatedAt)).limit(limit).map(InMemorySagaStore::copy).toList();
    }

    private static SagaInstance copy(SagaInstance s) {
        return SagaInstance.restore(s.id(), s.guestId(), s.idempotencyKey(), s.correlationId(), s.status(),
                s.completedSteps(), s.failedStep(), s.errorDetail(), s.reservaId(), s.pagoId(), s.reembolsoId(),
                s.createdAt(), s.updatedAt());
    }
}
