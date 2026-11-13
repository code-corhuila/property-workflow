package co.edu.corhuila.property.workflow.adapter.out.participants;

import co.edu.corhuila.property.workflow.application.port.out.Participants.PagoRegistrado;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment as the Saga uses it (payment-service.yaml): E-20, E-21 and E-08. The card
 * token goes in the body of E-20 and nowhere else: it is not stored, not logged and
 * never part of an error detail.
 */
public class HttpPayment implements Payment {

    private final ParticipantClient client;

    public HttpPayment(ParticipantClient client) {
        this.client = client;
    }

    @Override
    public PagoRegistrado cobrar(String key, String correlationId, UUID reservaId, UUID usuarioId, long montoCents,
                                 String moneda, String metodoPago, String tokenPago) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservaId", reservaId.toString());
        body.put("usuarioId", usuarioId.toString());
        body.put("montoCents", montoCents);
        body.put("moneda", moneda);
        body.put("metodoPago", metodoPago);
        body.put("tokenPago", tokenPago);
        return pago(client.expectSuccess(client.call("api/v1/pagos", body, key, correlationId)));
    }

    @Override
    public UUID reembolsar(UUID pagoId, String key, String correlationId) {
        JsonNode reembolso = client.expectSuccess(client.call("api/v1/pagos/" + pagoId + "/reembolsos", Map.of(), key,
                correlationId));
        return UUID.fromString(reembolso.path("id").asText());
    }

    /** 404: no Pago is recorded for the Reserva, which does not prove that no charge was attempted. */
    @Override
    public Optional<PagoRegistrado> pagoDeLaReserva(UUID reservaId, String correlationId) {
        ParticipantClient.Answer answer = client.call("api/v1/pagos/por-reserva/" + reservaId, null, null, correlationId);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(pago(client.expectSuccess(answer)));
    }

    private static PagoRegistrado pago(JsonNode pago) {
        String motivo = pago.path("motivoRechazo").isTextual() ? pago.path("motivoRechazo").asText() : null;
        return new PagoRegistrado(UUID.fromString(pago.path("id").asText()), "APROBADO".equals(pago.path("estado").asText()),
                motivo);
    }
}
