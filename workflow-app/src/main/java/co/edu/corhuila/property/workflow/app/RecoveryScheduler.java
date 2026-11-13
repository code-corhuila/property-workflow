package co.edu.corhuila.property.workflow.app;

import co.edu.corhuila.property.workflow.application.usecase.RecoverStaleSagas;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs the recovery routine of HU-SAGA-004 once the workflow is up and then every
 * interval, on one thread: a run never overlaps the previous one.
 */
final class RecoveryScheduler implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RecoverStaleSagas recovery;
    private final Duration interval;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "saga-recovery");
        thread.setDaemon(true);
        return thread;
    });

    RecoveryScheduler(RecoverStaleSagas recovery, Duration interval) {
        this.recovery = recovery;
        this.interval = interval;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runOnce() {
        try {
            recovery.recover();
        } catch (RuntimeException e) {     // the next run tries again; the executor must not die
            log.error("saga recovery run failed", e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
