package co.edu.corhuila.property.workflow.application.usecase;

import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.COBRAR;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CONFIRMAR_RESERVA;
import static co.edu.corhuila.property.workflow.domain.saga.SagaStep.CREAR_RESERVA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.application.port.in.StartReservarSaga.IniciarReservar;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepRejectedException;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepUnavailableException;
import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.property.workflow.domain.saga.UnknownSagaException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The tables of workflow-endpoint-fiches.md, row by row, against simulated participants. */
class RunReservarSagaTest {

    static final UUID SAGA = UUID.fromString("35920dc9-bd55-41a0-8e41-5832377383e5");
    static final UUID RESERVA = UUID.fromString("55555555-5555-4555-8555-555555555555");
    static final UUID PAGO = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID REEMBOLSO = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    static final IniciarReservar REQUEST = new IniciarReservar(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            LocalDate.parse("2026-12-10"), LocalDate.parse("2026-12-15"), 2,
            "TARJETA_CREDITO", "tok_visa", "reservar-0001", "trace-1");

    static final PagoRegistrado APROBADO = new PagoRegistrado(PAGO, true, null);

    /** Simulated Booking, Payment and store. Each call is journaled with its key; behaviour is per call. */
    static class World implements Booking, Payment, SagaStore {
        final List<String> calls = new ArrayList<>();
        final Map<String, Supplier<?>> answers = new HashMap<>();
        final List<String> saves = new ArrayList<>();
        final Map<String, SagaInstance> byKey = new HashMap<>();
        final Map<UUID, SagaInstance> byId = new HashMap<>();

        World() {
            on("crearReserva", () -> new ReservaCreada(RESERVA, 450000, "COP"));
            on("cobrar", () -> APROBADO);
            on("confirmar", () -> null);
            on("cancelar", () -> null);
            on("reembolsar", () -> REEMBOLSO);
        }

        World on(String call, Supplier<?> answer) {
            answers.put(call, answer);
            return this;
        }

        @SuppressWarnings("unchecked")
        private <T> T call(String name, String detail) {
            calls.add(detail == null ? name : name + " " + detail);
            return (T) answers.get(name).get();
        }

        public ReservaCreada crearReserva(String key, String corr, UUID u, UUID p, LocalDate i, LocalDate f, int h) {
            return call("crearReserva", key);
        }
        public void confirmar(UUID id, String key, String corr) { call("confirmar", key); }
        public void cancelar(UUID id, String motivo, String key, String corr) { call("cancelar", motivo + " " + key); }
        public EstadoReserva leerReserva(UUID id, String corr) { return call("leerReserva", null); }
        public PagoRegistrado cobrar(String key, String corr, UUID r, UUID u, long monto, String moneda, String m, String t) {
            return call("cobrar", key + " " + monto);
        }
        public UUID reembolsar(UUID pagoId, String key, String corr) { return call("reembolsar", key); }
        public Optional<PagoRegistrado> pagoDeLaReserva(UUID id, String corr) { return call("pagoDeLaReserva", null); }

        public CreateResult create(SagaInstance saga) {
            SagaInstance existing = byKey.putIfAbsent(saga.idempotencyKey(), saga);
            if (existing != null) {
                return new CreateResult(existing, false);
            }
            byId.put(saga.id(), saga);
            return new CreateResult(saga, true);
        }
        public void save(SagaInstance saga) { saves.add(saga.status() + " " + saga.completedSteps()); }
        public Optional<SagaInstance> get(UUID id) { return Optional.ofNullable(byId.get(id)); }
    }

    static Supplier<?> rejected(int status) {
        return () -> { throw new StepRejectedException(status, "answered " + status); };
    }

    static Supplier<?> unavailable() {
        return () -> { throw new StepUnavailableException("no answer"); };
    }

    static SagaInstance run(World world) {
        return new RunReservarSaga(world, world, world, () -> SAGA).start(REQUEST).saga();
    }

    @Test
    void theHappyPathRunsTheThreeStepsInOrderWithTheirKeys() {
        World world = new World();
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertEquals(List.of(CREAR_RESERVA, COBRAR, CONFIRMAR_RESERVA), saga.completedSteps());
        assertEquals(List.of("crearReserva " + SAGA + ":crear-reserva",
                "cobrar " + SAGA + ":cobrar 450000",            // the total comes from Booking (INV-005)
                "confirmar " + SAGA + ":confirmar-reserva"), world.calls);
        assertNull(saga.failedStep());
        assertEquals(RESERVA, saga.reservaId());
        assertEquals(PAGO, saga.pagoId());
    }

    @Test
    void theStateIsSavedAfterEveryStep() {
        World world = new World();
        run(world);

        assertEquals(List.of("RUNNING [CREAR_RESERVA]", "RUNNING [CREAR_RESERVA, COBRAR]",
                "COMPLETED [CREAR_RESERVA, COBRAR, CONFIRMAR_RESERVA]"), world.saves);
    }

    @Test
    void whenCrearReservaFailsNothingIsCancelled() {
        for (Supplier<?> failure : List.of(rejected(422), unavailable())) {
            World world = new World().on("crearReserva", failure);
            SagaInstance saga = run(world);

            assertEquals(SagaStatus.COMPENSATED, saga.status());
            assertEquals(CREAR_RESERVA, saga.failedStep());
            assertEquals(1, world.calls.size());
        }
    }

    @Test
    void aRejectedChargeCancelsTheReservaWithTheMotivoRechazo() {
        World world = new World().on("cobrar", () -> new PagoRegistrado(PAGO, false, "FONDOS_INSUFICIENTES"));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(COBRAR, saga.failedStep());
        assertEquals(PAGO, saga.pagoId());
        assertEquals("cancelar FONDOS_INSUFICIENTES " + SAGA + ":crear-reserva:compensar", world.calls.get(2));
    }

    @Test
    void aChargeAnswered4xxChargedNothingAndCancelsWithSagaCompensada() {
        World world = new World().on("cobrar", rejected(400));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals("cancelar SAGA_COMPENSADA " + SAGA + ":crear-reserva:compensar", world.calls.get(2));
    }

    @Test
    void aConfirmationAnswered4xxRefundsThenCancelsInReverseOrder() {
        World world = new World().on("confirmar", rejected(404));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals(CONFIRMAR_RESERVA, saga.failedStep());
        assertEquals(REEMBOLSO, saga.reembolsoId());
        assertEquals(List.of("reembolsar " + SAGA + ":cobrar:compensar",
                "cancelar SAGA_COMPENSADA " + SAGA + ":crear-reserva:compensar"), world.calls.subList(3, 5));
    }

    @Test
    void anUnknownConfirmationThatTookEffectCompletes() {
        World world = new World().on("confirmar", unavailable())
                .on("leerReserva", () -> EstadoReserva.CONFIRMADA)
                .on("pagoDeLaReserva", () -> Optional.of(APROBADO));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPLETED, saga.status());
        assertNull(saga.failedStep());
        assertFalse(world.calls.stream().anyMatch(c -> c.startsWith("reembolsar") || c.startsWith("cancelar")));
    }

    @Test
    void aConfirmationAnswered422OnAPendienteWithAnApprovedChargeRefundsAndCancels() {
        World world = new World().on("confirmar", rejected(422))
                .on("leerReserva", () -> EstadoReserva.PENDIENTE)
                .on("pagoDeLaReserva", () -> Optional.of(APROBADO));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertTrue(world.calls.contains("reembolsar " + SAGA + ":cobrar:compensar"));
        assertTrue(world.calls.contains("cancelar SAGA_COMPENSADA " + SAGA + ":crear-reserva:compensar"));
    }

    @Test
    void anUnknownChargeThatTheGatewayDeclinedCancelsWithItsMotivo() {
        World world = new World().on("cobrar", unavailable())
                .on("leerReserva", () -> EstadoReserva.PENDIENTE)
                .on("pagoDeLaReserva", () -> Optional.of(new PagoRegistrado(PAGO, false, "TARJETA_VENCIDA")));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertEquals("cancelar TARJETA_VENCIDA " + SAGA + ":crear-reserva:compensar", world.calls.get(world.calls.size() - 1));
    }

    @Test
    void whenNoMoneyMovementCanBeRuledOutTheSagaIsFailedAndNothingIsUndone() {
        List<Supplier<?>> pagos = List.of(Optional::empty,
                () -> Optional.of(new PagoRegistrado(PAGO, false, "PASARELA_NO_DISPONIBLE")));
        for (Supplier<?> pago : pagos) {
            World world = new World().on("cobrar", unavailable())
                    .on("leerReserva", () -> EstadoReserva.PENDIENTE).on("pagoDeLaReserva", pago);
            SagaInstance saga = run(world);

            assertEquals(SagaStatus.FAILED, saga.status());
            assertEquals(COBRAR, saga.failedStep());
            assertFalse(world.calls.stream().anyMatch(c -> c.startsWith("reembolsar") || c.startsWith("cancelar")));
        }
    }

    @Test
    void anExpiredReservaWithAnApprovedChargeIsOnlyRefunded() {
        World world = new World().on("confirmar", unavailable())
                .on("leerReserva", () -> EstadoReserva.EXPIRADA)
                .on("pagoDeLaReserva", () -> Optional.of(APROBADO));
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.COMPENSATED, saga.status());
        assertTrue(world.calls.contains("reembolsar " + SAGA + ":cobrar:compensar"));
        assertFalse(world.calls.stream().anyMatch(c -> c.startsWith("cancelar")));
    }

    @Test
    void aFailedReadEndsFailed() {
        World world = new World().on("confirmar", unavailable()).on("leerReserva", unavailable());

        assertEquals(SagaStatus.FAILED, run(world).status());
    }

    @Test
    void aFailedRefundEndsFailedAndTheCancelIsNotAttempted() {
        World world = new World().on("confirmar", rejected(404)).on("reembolsar", unavailable());
        SagaInstance saga = run(world);

        assertEquals(SagaStatus.FAILED, saga.status());
        assertFalse(world.calls.stream().anyMatch(c -> c.startsWith("cancelar")));
    }

    @Test
    void aCancelAnswered422SettlesOnTheReserva() {
        World expired = new World().on("cobrar", rejected(400)).on("cancelar", rejected(422))
                .on("leerReserva", () -> EstadoReserva.EXPIRADA);
        World confirmed = new World().on("cobrar", rejected(400)).on("cancelar", rejected(422))
                .on("leerReserva", () -> EstadoReserva.CONFIRMADA);

        assertEquals(SagaStatus.COMPENSATED, run(expired).status());
        assertEquals(SagaStatus.FAILED, run(confirmed).status());
    }

    @Test
    void theSameKeyNeverStartsASecondSagaNorRunsAStepAgain() {
        World world = new World();
        RunReservarSaga useCase = new RunReservarSaga(world, world, world, () -> SAGA);
        var first = useCase.start(REQUEST);
        var again = useCase.start(REQUEST);

        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.saga().id(), again.saga().id());
        assertEquals(3, world.calls.size());
        assertEquals(first.saga(), useCase.get(SAGA));
        assertThrows(UnknownSagaException.class, () -> useCase.get(RESERVA));
    }
}
