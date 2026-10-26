package co.edu.corhuila.property.workflow.application.port.out;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The two participants of the Saga reservar (ADR-009). Every call of a step or of a
 * compensation carries its Idempotency-Key and the correlation id; the adapter adds
 * the workflow's service token, the time limits and the bounded retries.
 *
 * A call ends in one of three ways: an answer; {@link StepRejectedException}, a 4xx
 * that was not retried, so the participant did not apply the call; or
 * {@link StepUnavailableException}, no answer or a 5xx after the retries, so the
 * workflow does not know whether the call took effect.
 */
public final class Participants {

    private Participants() { }

    public interface Booking {
        /** E-01 · POST /reservas. The total comes from Booking (INV-005). */
        ReservaCreada crearReserva(String key, String correlationId, UUID usuarioId, UUID propiedadId,
                                   LocalDate fechaInicio, LocalDate fechaFin, int huespedes);

        /** E-18 · POST /reservas/{id}/confirmar. */
        void confirmar(UUID reservaId, String key, String correlationId);

        /** E-04 · POST /reservas/{id}/cancelar. Repeating it on a CANCELADA Reserva changes nothing. */
        void cancelar(UUID reservaId, String motivo, String key, String correlationId);

        /** E-03 · GET /reservas/{id}. */
        EstadoReserva leerReserva(UUID reservaId, String correlationId);
    }

    public interface Payment {
        /** E-20 · POST /pagos. A rejected charge is an answer, not an error. */
        PagoRegistrado cobrar(String key, String correlationId, UUID reservaId, UUID usuarioId,
                              long montoCents, String moneda, String metodoPago, String tokenPago);

        /** E-21 · POST /pagos/{id}/reembolsos. Returns the id of the Reembolso. */
        UUID reembolsar(UUID pagoId, String key, String correlationId);

        /** E-08 · GET /pagos/por-reserva/{reservaId}. Empty when Payment recorded no Pago (404). */
        Optional<PagoRegistrado> pagoDeLaReserva(UUID reservaId, String correlationId);
    }

    public record ReservaCreada(UUID reservaId, long montoTotalCents, String moneda) { }

    public enum EstadoReserva { PENDIENTE, CONFIRMADA, CANCELADA, EXPIRADA }

    public record PagoRegistrado(UUID pagoId, boolean aprobado, String motivoRechazo) {

        /** A decline code of the gateway: the charge was attempted and refused. */
        public boolean rechazadoPorLaPasarela() {
            return !aprobado && !PASARELA_NO_DISPONIBLE.equals(motivoRechazo);
        }
    }

    /** The gateway could not be used: a charge may still exist there (workflow-endpoint-fiches.md). */
    public static final String PASARELA_NO_DISPONIBLE = "PASARELA_NO_DISPONIBLE";

    /** The participant answered a 4xx: it did not apply the call. */
    public static class StepRejectedException extends RuntimeException {
        private final int status;

        public StepRejectedException(int status, String detail) {
            super(detail);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** No answer, or a 5xx after the retries: the outcome is not known. */
    public static class StepUnavailableException extends RuntimeException {
        public StepUnavailableException(String detail) {
            super(detail);
        }
    }
}
