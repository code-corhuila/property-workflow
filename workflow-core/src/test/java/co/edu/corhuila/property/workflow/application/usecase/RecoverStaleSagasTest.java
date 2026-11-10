package co.edu.corhuila.property.workflow.application.usecase;

import static co.edu.corhuila.property.workflow.application.usecase.RunReservarSagaTest.APROBADO;
import static co.edu.corhuila.property.workflow.application.usecase.RunReservarSagaTest.REEMBOLSO;
import static co.edu.corhuila.property.workflow.application.usecase.RunReservarSagaTest.RESERVA;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.COBRAR;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CONFIRMAR_RESERVA;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CREAR_RESERVA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.property.workflow.domain.saga.SagaStep;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-SAGA-004: a Saga left RUNNING is settled from what the participants say, never replayed. */
class RecoverStaleSagasTest {

    static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    final RunReservarSagaTest.World world = new RunReservarSagaTest.World();
    final RunReservarSaga saga = new RunReservarSaga(world, world, world, UUID::randomUUID);

    static SagaInstance leftRunning(UUID reservaId, UUID pagoId, SagaStep... done) {
        return SagaInstance.restore(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(), "trace",
                SagaStatus.RUNNING, List.of(done), null, null, reservaId, pagoId, null, NOW.minusSeconds(600),
                NOW.minusSeconds(600));
    }

    boolean noStepRanForward() {
        return world.calls.stream().noneMatch(c -> c.startsWith("crearReserva") || c.startsWith("cobrar")
                || c.startsWith("confirmar"));
    }

    @Test
    void withoutAReservaItEndsCompensatedAtCrearReserva() {
        SagaInstance s = saga.recover(leftRunning(null, null));

        assertEquals(SagaStatus.COMPENSATED, s.status());
        assertEquals(CREAR_RESERVA, s.failedStep());
        assertTrue(world.calls.isEmpty(), world.calls.toString());
    }

    @Test
    void scenario1AConfirmedReservaEndsCompleted() {
        world.on("leerReserva", () -> EstadoReserva.CONFIRMADA).on("pagoDeLaReserva", () -> Optional.of(APROBADO));

        SagaInstance s = saga.recover(leftRunning(RESERVA, null, CREAR_RESERVA, COBRAR));
        assertEquals(SagaStatus.COMPLETED, s.status());
        assertEquals(List.of(CREAR_RESERVA, COBRAR, CONFIRMAR_RESERVA), s.completedSteps());
        assertNull(s.failedStep());
        assertTrue(noStepRanForward(), world.calls.toString());
    }

    @Test
    void anApprovedChargeOfAPendingReservaIsRefundedAndTheReservaCancelled() {
        world.on("leerReserva", () -> EstadoReserva.PENDIENTE).on("pagoDeLaReserva", () -> Optional.of(APROBADO));

        SagaInstance s = saga.recover(leftRunning(RESERVA, null, CREAR_RESERVA, COBRAR));
        assertEquals(SagaStatus.COMPENSATED, s.status());
        assertEquals(CONFIRMAR_RESERVA, s.failedStep());
        assertEquals(REEMBOLSO, s.reembolsoId());
        assertTrue(noStepRanForward(), world.calls.toString());
    }

    @Test
    void scenario2WhenItCannotProveThatNoMoneyMovedItEndsFailed() {
        world.on("leerReserva", () -> EstadoReserva.PENDIENTE).on("pagoDeLaReserva", Optional::empty);

        SagaInstance s = saga.recover(leftRunning(RESERVA, null, CREAR_RESERVA));
        assertEquals(SagaStatus.FAILED, s.status());
        assertEquals(COBRAR, s.failedStep());
        assertTrue(noStepRanForward(), world.calls.toString());
    }

    @Test
    void scenario3TheRoutineSettlesTheStaleSagasAndAFailureDoesNotStopTheBatch() {
        SagaInstance broken = leftRunning(RESERVA, null, CREAR_RESERVA);
        SagaInstance clean = leftRunning(null, null);
        RunReservarSagaTest.World store = new RunReservarSagaTest.World() {
            @Override
            public List<SagaInstance> findStale(Instant before, int limit) {
                assertEquals(NOW.minusSeconds(60), before);
                return List.of(broken, clean);
            }

            @Override
            public void save(SagaInstance s) {
                if (s == broken) {
                    throw new IllegalStateException("database down");
                }
                super.save(s);
            }
        };
        store.on("leerReserva", () -> EstadoReserva.CANCELADA).on("pagoDeLaReserva", Optional::empty);
        RunReservarSaga use = new RunReservarSaga(store, store, store, UUID::randomUUID);

        int settled = new RecoverStaleSagas(store, use, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60), 50).recover();
        assertEquals(1, settled);
        assertEquals(SagaStatus.COMPENSATED, clean.status());
    }
}
