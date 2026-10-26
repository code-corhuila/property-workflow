package co.edu.corhuila.property.workflow.application.usecase;

import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.COBRAR;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CONFIRMAR_RESERVA;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CREAR_RESERVA;

import co.edu.corhuila.property.workflow.application.port.in.GetSaga;
import co.edu.corhuila.property.workflow.application.port.in.StartReservarSaga;
import co.edu.corhuila.property.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepRejectedException;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepUnavailableException;
import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.UnknownSagaException;
import java.util.Optional;
import java.util.UUID;

/**
 * The Saga reservar (ADR-009, workflow-endpoint-fiches.md): crear-reserva, cobrar,
 * confirmar-reserva. When a step fails the completed ones are undone in reverse
 * order: refund, then cancel. When the workflow cannot know whether a step took
 * effect it stops calling it, reads the Reserva and the Pago, and acts on what it
 * read. The state is saved after every step and every compensation.
 */
public class RunReservarSaga implements StartReservarSaga, GetSaga {

    static final String SAGA_COMPENSADA = "SAGA_COMPENSADA";
    private static final int UNPROCESSABLE = 422;

    private final Booking booking;
    private final Payment payment;
    private final SagaStore store;
    private final IdGenerator ids;

    public RunReservarSaga(Booking booking, Payment payment, SagaStore store, IdGenerator ids) {
        this.booking = booking;
        this.payment = payment;
        this.store = store;
        this.ids = ids;
    }

    @Override
    public SagaInstance get(UUID id) {
        return store.get(id).orElseThrow(() -> new UnknownSagaException(id));
    }

    @Override
    public StartResult start(IniciarReservar r) {
        SagaStore.CreateResult created = store.create(
                new SagaInstance(ids.newId(), r.usuarioId(), r.idempotencyKey(), r.correlationId()));
        if (!created.created()) {
            return new StartResult(created.saga(), false); // a retry of the request: no step runs again
        }
        SagaInstance saga = created.saga();
        run(saga, r);
        store.save(saga);
        return new StartResult(saga, true);
    }

    private void run(SagaInstance saga, IniciarReservar r) {
        String corr = saga.correlationId();

        ReservaCreada reserva;
        try {
            reserva = booking.crearReserva(saga.keyOf(CREAR_RESERVA), corr, r.usuarioId(), r.propiedadId(),
                    r.fechaInicio(), r.fechaFin(), r.huespedes());
        } catch (StepRejectedException | StepUnavailableException e) {
            // No reservaId: nothing to cancel. A Reserva created with a lost answer is expired by the worker.
            saga.stepFailed(CREAR_RESERVA, e.getMessage());
            saga.compensated();
            return;
        }
        saga.reservaCreada(reserva.reservaId());
        saga.stepDone(CREAR_RESERVA);
        store.save(saga);

        PagoRegistrado pago;
        try {
            pago = payment.cobrar(saga.keyOf(COBRAR), corr, reserva.reservaId(), r.usuarioId(),
                    reserva.montoTotalCents(), reserva.moneda(), r.metodoPago(), r.tokenPago());
        } catch (StepRejectedException e) {
            saga.stepFailed(COBRAR, e.getMessage());   // nothing was charged
            cancel(saga, SAGA_COMPENSADA);
            return;
        } catch (StepUnavailableException e) {
            saga.stepFailed(COBRAR, e.getMessage());
            settle(saga);
            return;
        }
        saga.pagoRegistrado(pago.pagoId());
        if (!pago.aprobado()) {
            saga.stepFailed(COBRAR, "rejected: " + pago.motivoRechazo());
            cancel(saga, pago.motivoRechazo());
            return;
        }
        saga.stepDone(COBRAR);
        store.save(saga);

        try {
            booking.confirmar(reserva.reservaId(), saga.keyOf(CONFIRMAR_RESERVA), corr);
        } catch (StepRejectedException e) {
            saga.stepFailed(CONFIRMAR_RESERVA, e.getMessage());
            if (e.status() == UNPROCESSABLE) {
                settle(saga);                 // 422: did a previous attempt confirm it?
            } else if (refund(saga)) {
                cancel(saga, SAGA_COMPENSADA);
            }
            return;
        } catch (StepUnavailableException e) {
            saga.stepFailed(CONFIRMAR_RESERVA, e.getMessage());
            settle(saga);
            return;
        }
        saga.stepDone(CONFIRMAR_RESERVA);
        saga.complete();
    }

    /**
     * The workflow does not know whether a step took effect: it reads the Reserva and
     * the Pago and acts on what it read. When it cannot prove that no money moved, the
     * Saga ends FAILED and a person decides.
     */
    private void settle(SagaInstance saga) {
        EstadoReserva estado;
        Optional<PagoRegistrado> pago;
        try {
            estado = booking.leerReserva(saga.reservaId(), saga.correlationId());
            pago = payment.pagoDeLaReserva(saga.reservaId(), saga.correlationId());
        } catch (RuntimeException e) {
            saga.fail("cannot read the outcome: " + e.getMessage());
            return;
        }
        pago.ifPresent(p -> saga.pagoRegistrado(p.pagoId()));
        boolean aprobado = pago.map(PagoRegistrado::aprobado).orElse(false);
        boolean rechazado = pago.map(PagoRegistrado::rechazadoPorLaPasarela).orElse(false);

        if (estado == EstadoReserva.CONFIRMADA) {
            if (aprobado && !saga.completedSteps().contains(COBRAR)) {
                saga.stepDone(COBRAR);
            }
            saga.stepDone(CONFIRMAR_RESERVA);
            saga.complete();                  // the confirmation took effect
        } else if (estado == EstadoReserva.PENDIENTE && aprobado) {
            if (refund(saga)) {
                cancel(saga, SAGA_COMPENSADA);
            }
        } else if (estado == EstadoReserva.PENDIENTE && rechazado) {
            cancel(saga, pago.get().motivoRechazo());
        } else if ((estado == EstadoReserva.CANCELADA || estado == EstadoReserva.EXPIRADA) && aprobado) {
            if (refund(saga)) {
                saga.compensated();           // the dates are already free
            }
        } else if ((estado == EstadoReserva.CANCELADA || estado == EstadoReserva.EXPIRADA) && rechazado) {
            saga.compensated();
        } else {
            // No Pago, or PASARELA_NO_DISPONIBLE: a charge Payment did not record may exist at the gateway.
            saga.fail("the outcome of the charge cannot be established: Reserva " + estado
                    + ", Pago " + pago.map(p -> p.aprobado() ? "APROBADO" : p.motivoRechazo()).orElse("none"));
        }
    }

    /** Compensation of cobrar. After a failed refund the cancel is not attempted: the worker frees the dates. */
    private boolean refund(SagaInstance saga) {
        try {
            saga.reembolsoRegistrado(payment.reembolsar(saga.pagoId(), saga.compensationKeyOf(COBRAR),
                    saga.correlationId()));
            store.save(saga);
            return true;
        } catch (RuntimeException e) {
            saga.fail("compensation of cobrar: " + e.getMessage());
            return false;
        }
    }

    /** Compensation of crear-reserva. A 422 means the Reserva left PENDIENTE: read it. */
    private void cancel(SagaInstance saga, String motivo) {
        try {
            booking.cancelar(saga.reservaId(), motivo, saga.compensationKeyOf(CREAR_RESERVA), saga.correlationId());
            saga.compensated();
        } catch (StepRejectedException e) {
            if (e.status() == UNPROCESSABLE && expired(saga)) {
                saga.compensated();           // EXPIRADA: the dates are already free
            } else {
                saga.fail("compensation of crear-reserva: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            saga.fail("compensation of crear-reserva: " + e.getMessage());
        }
    }

    private boolean expired(SagaInstance saga) {
        try {
            return booking.leerReserva(saga.reservaId(), saga.correlationId()) == EstadoReserva.EXPIRADA;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
