package co.edu.corhuila.property.workflow.application.port.in;

import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import java.time.LocalDate;
import java.util.UUID;

/** E-23 · POST /sagas/reservar. */
public interface StartReservarSaga {

    /**
     * The validated request. usuarioId is the sub of the token, never a field of
     * the body; the amount comes from Booking, never from the client.
     */
    record IniciarReservar(UUID usuarioId, UUID propiedadId, LocalDate fechaInicio, LocalDate fechaFin,
                           int huespedes, String metodoPago, String tokenPago,
                           String idempotencyKey, String correlationId) { }

    /** created is false when the key had already started a Saga: it is returned and no step runs again. */
    record StartResult(SagaInstance saga, boolean created) { }

    StartResult start(IniciarReservar request);
}
