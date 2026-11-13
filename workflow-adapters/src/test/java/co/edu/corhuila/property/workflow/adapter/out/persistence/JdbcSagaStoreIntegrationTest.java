package co.edu.corhuila.property.workflow.adapter.out.persistence;

import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.COBRAR;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CREAR_RESERVA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * workflow.saga_instance against the REAL engine with deploy/db applied, as workflow_app.
 * Skipped without TEST_DATABASE_URL (Annex C); scripts/test-db.sh prepares one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcSagaStoreIntegrationTest {

    JdbcSagaStore store;

    @BeforeEach
    void connect() {
        store = new JdbcSagaStore(new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"),
                System.getenv("TEST_DATABASE_USER"), System.getenv("TEST_DATABASE_PASSWORD"))));
    }

    static SagaInstance saga(String key) {
        return new SagaInstance(UUID.randomUUID(), UUID.randomUUID(), key, "e2e-trace-not-a-uuid");
    }

    @Test
    void aSagaIsStoredAndEveryStepIsSavedAsItWas() {
        SagaInstance s = saga("it-" + UUID.randomUUID());
        assertTrue(store.create(s).created());

        UUID reserva = UUID.randomUUID();
        UUID pago = UUID.randomUUID();
        s.reservaCreada(reserva);
        s.stepDone(CREAR_RESERVA);
        s.pagoRegistrado(pago);
        s.stepFailed(COBRAR, "rejected: FONDOS_INSUFICIENTES");
        s.compensated();
        store.save(s);

        SagaInstance read = store.get(s.id()).orElseThrow();
        assertEquals(SagaStatus.COMPENSATED, read.status());
        assertEquals(List.of(CREAR_RESERVA), read.completedSteps());
        assertEquals(COBRAR, read.failedStep());
        assertEquals("cobrar: rejected: FONDOS_INSUFICIENTES", read.errorDetail());
        assertEquals(reserva, read.reservaId());
        assertEquals(pago, read.pagoId());
        assertEquals("e2e-trace-not-a-uuid", read.correlationId());
        assertEquals(s.createdAt(), read.createdAt());
        assertEquals(s.updatedAt(), read.updatedAt());
    }

    @Test
    void twoStartsWithTheSameKeyKeepOneSaga() {
        String key = "it-" + UUID.randomUUID();
        var a = CompletableFuture.supplyAsync(() -> store.create(saga(key)));
        var b = CompletableFuture.supplyAsync(() -> store.create(saga(key)));

        assertEquals(a.join().saga().id(), b.join().saga().id());
        assertTrue(a.join().created() ^ b.join().created());
    }

    @Test
    void theStaleOnesAreTheRunningSagasNothingTouchedSinceAnInstant() throws Exception {
        SagaInstance running = saga("it-" + UUID.randomUUID());
        store.create(running);
        SagaInstance done = saga("it-" + UUID.randomUUID());
        store.create(done);
        done.complete();
        store.save(done);
        Thread.sleep(5);
        Instant cutoff = Instant.now();

        List<UUID> stale = store.findStale(cutoff, 1000).stream().map(SagaInstance::id).toList();
        assertTrue(stale.contains(running.id()));
        assertFalse(stale.contains(done.id()));
        assertFalse(store.findStale(running.updatedAt(), 1000).stream().anyMatch(s -> s.id().equals(running.id())));
    }
}
