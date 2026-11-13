package co.edu.corhuila.property.workflow.application.usecase;

import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * The recovery routine of HU-SAGA-004 (workflow-endpoint-fiches.md, Recovery). It runs
 * when the workflow starts and then periodically, and settles the Sagas that stayed
 * RUNNING longer than staleAfter: the request ran out of time, or the workflow
 * restarted. staleAfter is longer than the time limit of the request plus one call to a
 * participant, so the request and the routine never act on the same Saga together;
 * property-workflow runs as a single instance (deployment.md).
 */
public class RecoverStaleSagas {

    private static final System.Logger LOG = System.getLogger(RecoverStaleSagas.class.getName());

    private final SagaStore store;
    private final RunReservarSaga saga;
    private final Clock clock;
    private final Duration staleAfter;
    private final int batch;

    public RecoverStaleSagas(SagaStore store, RunReservarSaga saga, Clock clock, Duration staleAfter, int batch) {
        this.store = store;
        this.saga = saga;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.batch = batch;
    }

    /** Settles at most one batch; a Saga that cannot be settled now stays RUNNING for the next run. */
    public int recover() {
        List<SagaInstance> stale = store.findStale(clock.instant().minus(staleAfter), batch);
        int settled = 0;
        for (SagaInstance s : stale) {
            try {
                SagaInstance after = saga.recover(s);
                settled++;
                LOG.log(Level.INFO, "saga {0} recovered: {1}", s.id(), after.status());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "saga {0} could not be recovered now: {1}", s.id(), e.getMessage());
            }
        }
        return settled;
    }
}
