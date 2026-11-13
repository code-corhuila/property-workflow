package co.edu.corhuila.property.workflow.application.port.out;

import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the state of every Saga lives: the schema workflow (ADR-010). */
public interface SagaStore {

    record CreateResult(SagaInstance saga, boolean created) { }

    /**
     * Stores a new Saga under its idempotency key, atomically. If the key already
     * has a Saga, stores nothing and returns that one with created=false.
     */
    CreateResult create(SagaInstance saga);

    /** Called after every step and every compensation (ADR-009, rule 4). */
    void save(SagaInstance saga);

    Optional<SagaInstance> get(UUID id);

    /**
     * Sagas still RUNNING that nothing touched since that instant: the request that
     * started them ran out of time, or the workflow restarted (HU-SAGA-004). Oldest
     * first, never more than the limit.
     */
    List<SagaInstance> findStale(Instant updatedBefore, int limit);
}
