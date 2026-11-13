package co.edu.corhuila.property.workflow.app;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepRejectedException;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HU-SAGA-001, 002 and 003 over HTTP (E-23, E-24), with Booking and Payment simulated behind their ports. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SagaHttpTest {

    static final String TOKEN = "tok_card_secret_19ab";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    Booking booking;
    @MockitoBean
    Payment payment;

    UUID guest;
    UUID reserva;
    UUID pago;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    static String person(UUID id, String role) {
        return TestKeys.bearer("\"sub\":\"" + id + "\",\"roles\":[\"" + role + "\"]");
    }

    @BeforeEach
    void participantsThatSucceed() {
        guest = UUID.randomUUID();
        reserva = UUID.randomUUID();
        pago = UUID.randomUUID();
        when(booking.crearReserva(anyString(), anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new ReservaCreada(reserva, 250000000L, "COP"));
        when(payment.cobrar(anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new PagoRegistrado(pago, true, null));
    }

    ResultActions reservar(String key, String bearer) throws Exception {
        String body = "{\"propiedadId\":\"a1d5e2f0-3333-4c3e-9a2d-000000000003\",\"fechaInicio\":\"2026-09-10\","
                + "\"fechaFin\":\"2026-09-15\",\"huespedes\":2,\"metodoPago\":\"TARJETA_CREDITO\",\"tokenPago\":\"" + TOKEN + "\"}";
        var request = post("/api/v1/sagas/reservar").header("Authorization", bearer).header("X-Correlation-Id", "front-77")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    @Test
    void saga001Scenario1TheHappyPathCreatesChargesAndConfirmsInOrder() throws Exception {
        reservar("reservar-" + UUID.randomUUID(), person(guest, "GUEST")).andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/sagas/")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.reservaId", is(reserva.toString())))
                .andExpect(jsonPath("$.pagoId", is(pago.toString())))
                .andExpect(jsonPath("$.completedSteps", contains("crear-reserva", "cobrar", "confirmar-reserva")))
                .andExpect(jsonPath("$.failedStep").doesNotExist());
        verify(booking).crearReserva(keyWith(":crear-reserva"), eq("front-77"), eq(guest), any(), any(), any(), eq(2));
        verify(payment).cobrar(keyWith(":cobrar"), eq("front-77"), eq(reserva), eq(guest), eq(250000000L), eq("COP"),
                eq("TARJETA_CREDITO"), eq(TOKEN));
        verify(booking).confirmar(eq(reserva), keyWith(":confirmar-reserva"), eq("front-77"));
    }

    @Test
    void saga001Scenario2AnInvalidRequestIs400WithEveryFieldAndCreatesNoSaga() throws Exception {
        mvc.perform(post("/api/v1/sagas/reservar").header("Authorization", person(guest, "GUEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaInicio\":\"2026-09-15\",\"fechaFin\":\"2026-09-15\",\"huespedes\":0,\"metodoPago\":\"EFECTIVO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("propiedadId", "fechaFin", "huespedes",
                        "metodoPago", "tokenPago", "Idempotency-Key")));
        verify(booking, never()).crearReserva(anyString(), anyString(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void saga001Scenario3DatesTakenIs201CompensatedAtCrearReservaAndNothingIsCharged() throws Exception {
        when(booking.crearReserva(anyString(), anyString(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new StepRejectedException(422, "booking answered 422 BUSINESS_RULE_VIOLATION"));

        reservar("reservar-" + UUID.randomUUID(), person(guest, "GUEST")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("COMPENSATED")))
                .andExpect(jsonPath("$.failedStep", is("crear-reserva")))
                .andExpect(jsonPath("$.errorDetail").doesNotExist());
        verify(payment, never()).cobrar(anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void saga002Scenario1ARejectedChargeCancelsTheReservaWithItsMotivo() throws Exception {
        when(payment.cobrar(anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new PagoRegistrado(pago, false, "FONDOS_INSUFICIENTES"));

        reservar("reservar-" + UUID.randomUUID(), person(guest, "GUEST")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("COMPENSATED")))
                .andExpect(jsonPath("$.failedStep", is("cobrar")))
                .andExpect(jsonPath("$.reembolsoId").doesNotExist());
        verify(booking).cancelar(eq(reserva), eq("FONDOS_INSUFICIENTES"), keyWith(":crear-reserva:compensar"), eq("front-77"));
    }

    @Test
    void saga001Scenario4TheOwnerOrAnAdminReadsItAnotherGuestAndAServiceDoNot() throws Exception {
        String id = JsonPath.read(reservar("reservar-" + UUID.randomUUID(), person(guest, "GUEST"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/v1/sagas/" + id).header("Authorization", person(guest, "GUEST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        mvc.perform(get("/api/v1/sagas/" + id).header("Authorization", person(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sagas/" + id).header("Authorization", person(UUID.randomUUID(), "GUEST")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error", is("FORBIDDEN")));
        mvc.perform(get("/api/v1/sagas/" + id).header("Authorization", TestKeys.bearer("\"sub\":\"svc-worker\",\"scope\":\"service\"")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/sagas/" + UUID.randomUUID()).header("Authorization", person(guest, "GUEST")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sagas/not-a-uuid").header("Authorization", person(guest, "GUEST")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saga003Scenario1ARepeatedStartIs200WithTheSameSagaAndRunsNoStepAgain() throws Exception {
        String key = "reservar-" + UUID.randomUUID();
        String id = JsonPath.read(reservar(key, person(guest, "GUEST")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        reservar(key, person(guest, "GUEST")).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(id)));
        verify(booking, times(1)).crearReserva(anyString(), anyString(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void saga003Scenario3WithoutTheKeyIs400AndNamesTheHeader() throws Exception {
        reservar(null, person(guest, "GUEST")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.details[0].field", is("Idempotency-Key")));
    }

    @Test
    void aServiceCannotStartASagaAndTheCardTokenNeverReachesTheLog(CapturedOutput output) throws Exception {
        reservar("reservar-" + UUID.randomUUID(), TestKeys.bearer("\"sub\":\"svc-workflow\",\"scope\":\"service\""))
                .andExpect(status().isForbidden());
        reservar("reservar-" + UUID.randomUUID(), person(guest, "GUEST")).andExpect(status().isCreated());

        assertFalse(output.getOut().contains(TOKEN));
        assertFalse(output.getErr().contains(TOKEN));
    }

    private static String keyWith(String part) {
        return org.mockito.ArgumentMatchers.contains(part);
    }
}
