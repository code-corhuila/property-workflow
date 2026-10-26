package co.edu.corhuila.property.workflow.domain.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaInstanceTest {

    static final UUID ID = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");

    static SagaInstance saga() {
        return new SagaInstance(ID, UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "reservar-0001", "trace-1");
    }

    @Test
    void aNewSagaIsRunningWithNoStepDone() {
        SagaInstance saga = saga();

        assertEquals(SagaStatus.RUNNING, saga.status());
        assertEquals(List.of(), saga.completedSteps());
        assertNull(saga.failedStep());
        assertEquals("reservar", SagaInstance.TYPE);
    }

    @Test
    void everyStepAndEveryCompensationHasItsKey() {
        SagaInstance saga = saga();

        assertEquals(ID + ":crear-reserva", saga.keyOf(SagaStep.CREAR_RESERVA));
        assertEquals(ID + ":cobrar", saga.keyOf(SagaStep.COBRAR));
        assertEquals(ID + ":confirmar-reserva", saga.keyOf(SagaStep.CONFIRMAR_RESERVA));
        assertEquals(ID + ":crear-reserva:compensar", saga.compensationKeyOf(SagaStep.CREAR_RESERVA));
        assertEquals(ID + ":cobrar:compensar", saga.compensationKeyOf(SagaStep.COBRAR));
    }

    @Test
    void aFailedStepKeepsItsDetailInsideTheSaga() {
        SagaInstance saga = saga();
        saga.stepDone(SagaStep.CREAR_RESERVA);
        saga.stepFailed(SagaStep.COBRAR, "payment-api:3003 answered 503");
        saga.fail("compensation of crear-reserva: no answer");

        assertEquals(SagaStatus.FAILED, saga.status());
        assertEquals(SagaStep.COBRAR, saga.failedStep());
        assertEquals(List.of(SagaStep.CREAR_RESERVA), saga.completedSteps());
        assertTrue(saga.errorDetail().contains("payment-api:3003"));
        assertTrue(saga.errorDetail().contains("compensation of crear-reserva"));
    }

    @Test
    void onlyADeclineOfTheGatewayIsARejectedCharge() {
        assertTrue(new PagoRegistrado(ID, false, "TARJETA_RECHAZADA").rechazadoPorLaPasarela());
        assertEquals(false, new PagoRegistrado(ID, false, "PASARELA_NO_DISPONIBLE").rechazadoPorLaPasarela());
        assertEquals(false, new PagoRegistrado(ID, true, null).rechazadoPorLaPasarela());
    }
}
