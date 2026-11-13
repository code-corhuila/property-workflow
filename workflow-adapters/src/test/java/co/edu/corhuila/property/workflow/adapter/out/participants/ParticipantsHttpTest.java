package co.edu.corhuila.property.workflow.adapter.out.participants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepRejectedException;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The clients of the Saga against a fake participant: a real server on a free port with scripted answers. */
class ParticipantsHttpTest {

    record Answer(int status, String body, long delayMs) { }

    record Seen(String method, String path, String auth, String key, String correlation, String body) { }

    static final UUID ID = UUID.fromString("9cfcfeec-c4b6-4d76-a0b4-f0db602d7caa");

    HttpServer server;
    final Deque<Answer> answers = new ArrayDeque<>();
    final List<Seen> seen = new ArrayList<>();
    HttpBooking booking;
    HttpPayment payment;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var h = exchange.getRequestHeaders();
            synchronized (seen) {
                seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), h.getFirst("Authorization"),
                        h.getFirst("Idempotency-Key"), h.getFirst("X-Correlation-Id"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            Answer a = answers.size() > 1 ? answers.poll() : answers.peek();
            try {
                Thread.sleep(a.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var limits = new ParticipantClient.Limits(Duration.ofMillis(300), 2, Duration.ofMillis(10));
        booking = new HttpBooking(new ParticipantClient("booking", url, "svc-token", limits));
        payment = new HttpPayment(new ParticipantClient("payment", url, "svc-token", limits));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void answer(int status, String body) {
        answers.add(new Answer(status, body, 0));
    }

    @Test
    void crearReservaSendsTheStepKeyTheServiceTokenAndTheCorrelationId() {
        answer(201, "{\"id\":\"" + ID + "\",\"montoTotalCents\":250000000,\"moneda\":\"COP\",\"estado\":\"PENDIENTE\"}");

        ReservaCreada r = booking.crearReserva("saga-1:crear-reserva", "trace-1", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-15"), 2);
        assertEquals(new ReservaCreada(ID, 250000000L, "COP"), r);
        Seen s = seen.get(0);
        assertEquals("POST", s.method());
        assertEquals("/api/v1/reservas", s.path());
        assertEquals("Bearer svc-token", s.auth());
        assertEquals("saga-1:crear-reserva", s.key());
        assertEquals("trace-1", s.correlation());
        assertTrue(s.body().contains("\"fechaFin\":\"2026-09-15\"") && s.body().contains("\"huespedes\":2"), s.body());
    }

    @Test
    void a4xxIsRejectedAndNeverRetried() {
        answer(422, "{\"error\":\"INVALID_STATUS_TRANSITION\",\"message\":\"already CONFIRMADA\"}");

        var e = assertThrows(StepRejectedException.class, () -> booking.confirmar(ID, "saga-1:confirmar-reserva", "t"));
        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("INVALID_STATUS_TRANSITION"));
        assertEquals(1, seen.size());
    }

    @Test
    void aFailedAttemptIsRetriedWithTheSameKey() {
        answer(503, "");
        answer(200, "{\"id\":\"" + ID + "\",\"estado\":\"CANCELADA\"}");

        booking.cancelar(ID, "FONDOS_INSUFICIENTES", "saga-1:crear-reserva:compensar", "t");
        assertEquals(2, seen.size());
        assertEquals(seen.get(0).key(), seen.get(1).key());
        assertTrue(seen.get(1).body().contains("FONDOS_INSUFICIENTES"));
    }

    @Test
    void noAnswerAfterTheAttemptsIsUnavailable() {
        answer(500, "");
        assertThrows(StepUnavailableException.class, () -> booking.leerReserva(ID, "t"));
        assertEquals(2, seen.size());

        answers.clear();
        answers.add(new Answer(200, "{}", 1000));
        assertThrows(StepUnavailableException.class, () -> booking.leerReserva(ID, "t"));

        server.stop(0);
        assertThrows(StepUnavailableException.class, () -> booking.leerReserva(ID, "t"));
    }

    @Test
    void theReadsAnswerTheStateAndANoPagoIsEmpty() {
        answer(200, "{\"id\":\"" + ID + "\",\"estado\":\"EXPIRADA\"}");
        assertEquals(EstadoReserva.EXPIRADA, booking.leerReserva(ID, "t"));
        assertEquals("GET", seen.get(0).method());
        assertEquals(null, seen.get(0).key());

        answers.clear();
        answer(404, "{\"error\":\"NOT_FOUND\"}");
        assertTrue(payment.pagoDeLaReserva(ID, "t").isEmpty());
        assertEquals("/api/v1/pagos/por-reserva/" + ID, seen.get(1).path());
    }

    @Test
    void cobrarCarriesTheTokenOnlyInTheBodyAndReadsTheOutcome() {
        answer(201, "{\"id\":\"" + ID + "\",\"estado\":\"RECHAZADO\",\"motivoRechazo\":\"PASARELA_NO_DISPONIBLE\"}");

        PagoRegistrado p = payment.cobrar("saga-1:cobrar", "t", UUID.randomUUID(), UUID.randomUUID(), 250000000L, "COP",
                "TARJETA_CREDITO", "tok_visa");
        assertFalse(p.aprobado());
        assertFalse(p.rechazadoPorLaPasarela(), "PASARELA_NO_DISPONIBLE is not a decline");
        assertTrue(seen.get(0).body().contains("\"tokenPago\":\"tok_visa\""));
        assertEquals("saga-1:cobrar", seen.get(0).key());

        answers.clear();
        answer(500, "{\"error\":\"INTERNAL_ERROR\"}");
        var e = assertThrows(StepUnavailableException.class, () -> payment.cobrar("saga-2:cobrar", "t", UUID.randomUUID(),
                UUID.randomUUID(), 1L, "COP", "TARJETA_CREDITO", "tok_visa"));
        assertFalse(e.getMessage().contains("tok_visa"));
    }

    @Test
    void aRefundAnswersTheIdOfTheReembolso() {
        answer(201, "{\"id\":\"" + ID + "\",\"pagoId\":\"" + UUID.randomUUID() + "\"}");

        assertEquals(ID, payment.reembolsar(UUID.randomUUID(), "saga-1:cobrar:compensar", "t"));
        assertEquals("saga-1:cobrar:compensar", seen.get(0).key());
    }

    @Test
    void withoutTheServiceTokenTheWorkflowDoesNotStart() {
        var limits = new ParticipantClient.Limits(Duration.ofSeconds(1), 1, Duration.ofMillis(1));
        var e = assertThrows(IllegalStateException.class, () -> new ParticipantClient("booking", "http://x", " ", limits));
        assertTrue(e.getMessage().contains("SERVICE_TOKEN"));
    }
}
