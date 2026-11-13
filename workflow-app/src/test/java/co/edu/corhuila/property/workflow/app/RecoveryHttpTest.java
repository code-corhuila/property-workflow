package co.edu.corhuila.property.workflow.app;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.application.usecase.RecoverStaleSagas;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.property.workflow.domain.saga.SagaStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HU-SAGA-004 scenario 3: a Saga left RUNNING is settled afterwards and the guest reads its final state. */
@SpringBootTest
@AutoConfigureMockMvc
class RecoveryHttpTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SagaStore store;
    @Autowired
    RecoverStaleSagas recovery;
    @MockitoBean
    Booking booking;
    @MockitoBean
    Payment payment;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @Test
    void aSagaLeftRunningIsSettledAndTheGuestSeesHowItEnded() throws Exception {
        UUID guest = UUID.randomUUID();
        UUID reserva = UUID.randomUUID();
        UUID pago = UUID.randomUUID();
        Instant longAgo = Instant.now().minusSeconds(3600);
        SagaInstance left = SagaInstance.restore(UUID.randomUUID(), guest, "key-" + UUID.randomUUID(), "trace",
                SagaStatus.RUNNING, List.of(SagaStep.CREAR_RESERVA, SagaStep.COBRAR), null, null, reserva, pago, null,
                longAgo, longAgo);
        store.create(left);
        when(booking.leerReserva(any(), anyString())).thenReturn(EstadoReserva.CONFIRMADA);
        when(payment.pagoDeLaReserva(any(), anyString())).thenReturn(Optional.of(new PagoRegistrado(pago, true, null)));
        String bearer = TestKeys.bearer("\"sub\":\"" + guest + "\",\"roles\":[\"GUEST\"]");

        mvc.perform(get("/api/v1/sagas/" + left.id()).header("Authorization", bearer)).andExpect(jsonPath("$.status", is("RUNNING")));
        recovery.recover();
        mvc.perform(get("/api/v1/sagas/" + left.id()).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.completedSteps.length()", is(3)));
    }
}
