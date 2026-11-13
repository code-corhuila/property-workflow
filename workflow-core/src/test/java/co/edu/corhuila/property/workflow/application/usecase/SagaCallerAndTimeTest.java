package co.edu.corhuila.property.workflow.application.usecase;

import static co.edu.corhuila.property.workflow.application.usecase.RunReservarSagaTest.REQUEST;
import static co.edu.corhuila.property.workflow.application.usecase.RunReservarSagaTest.SAGA;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CREAR_RESERVA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.application.port.in.AccessDeniedException;
import co.edu.corhuila.property.workflow.application.port.in.Caller;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** E-23 and E-24 for a caller, and the time limit of the request (HU-SAGA-004, scenario 3). */
class SagaCallerAndTimeTest {

    static final UUID GUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** A clock the participants move forward. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final RunReservarSagaTest.World world = new RunReservarSagaTest.World();
    final MovingClock clock = new MovingClock();
    final RunReservarSaga useCase = new RunReservarSaga(world, world, world, () -> SAGA, clock, Duration.ofSeconds(25));

    static Caller person(UUID id, String role) {
        return new Caller(id.toString(), List.of(role), false);
    }

    @Test
    void theGuestOfTheReservationIsTheSubOfTheTokenAndAServiceCannotStartOne() {
        SagaInstance saga = useCase.start(person(GUEST, "HOST"), REQUEST).saga();

        assertEquals(GUEST, saga.guestId());
        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertThrows(AccessDeniedException.class,
                () -> useCase.start(new Caller("svc-workflow", null, true), REQUEST));
    }

    @Test
    void theGuestWhoStartedItOrAnAdminReadsItAnotherGuestOrAServiceDoesNot() {
        useCase.start(person(GUEST, "GUEST"), REQUEST);

        assertEquals(SAGA, useCase.get(person(GUEST, "GUEST"), SAGA).id());
        assertEquals(SAGA, useCase.get(person(UUID.randomUUID(), "ADMIN"), SAGA).id());
        assertThrows(AccessDeniedException.class, () -> useCase.get(person(UUID.randomUUID(), "GUEST"), SAGA));
        assertThrows(AccessDeniedException.class, () -> useCase.get(new Caller("svc-worker", null, true), SAGA));
    }

    @Test
    void whenTheRequestRunsOutOfTimeTheSagaStaysRunningAndNoFurtherStepIsCalled() {
        world.on("crearReserva", () -> {
            clock.now = clock.now.plusSeconds(26);       // step 1 took longer than the whole limit
            return new ReservaCreada(RunReservarSagaTest.RESERVA, 450000, "COP");
        });

        SagaInstance saga = useCase.start(person(GUEST, "GUEST"), REQUEST).saga();
        assertEquals(SagaStatus.RUNNING, saga.status());
        assertEquals(List.of(CREAR_RESERVA), saga.completedSteps());
        assertEquals(RunReservarSagaTest.RESERVA, saga.reservaId());
        assertTrue(world.calls.stream().noneMatch(c -> c.startsWith("cobrar")), world.calls.toString());
        assertEquals("RUNNING [CREAR_RESERVA]", world.saves.get(world.saves.size() - 1));
    }
}
