package co.edu.corhuila.property.workflow.adapter.out.participants;

import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.EstadoReserva;
import co.edu.corhuila.property.workflow.application.port.out.Participants.ReservaCreada;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Booking as the Saga uses it (booking-service.yaml): E-01, E-18, E-04 and E-03. */
public class HttpBooking implements Booking {

    private final ParticipantClient client;

    public HttpBooking(ParticipantClient client) {
        this.client = client;
    }

    @Override
    public ReservaCreada crearReserva(String key, String correlationId, UUID usuarioId, UUID propiedadId,
                                      LocalDate fechaInicio, LocalDate fechaFin, int huespedes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usuarioId", usuarioId.toString());
        body.put("propiedadId", propiedadId.toString());
        body.put("fechaInicio", fechaInicio.toString());
        body.put("fechaFin", fechaFin.toString());
        body.put("huespedes", huespedes);
        JsonNode reserva = client.expectSuccess(client.call("api/v1/reservas", body, key, correlationId));
        return new ReservaCreada(UUID.fromString(reserva.path("id").asText()), reserva.path("montoTotalCents").asLong(),
                reserva.path("moneda").asText());
    }

    @Override
    public void confirmar(UUID reservaId, String key, String correlationId) {
        client.expectSuccess(client.call("api/v1/reservas/" + reservaId + "/confirmar", Map.of(), key, correlationId));
    }

    @Override
    public void cancelar(UUID reservaId, String motivo, String key, String correlationId) {
        Map<String, Object> body = motivo == null ? Map.of() : Map.of("motivo", motivo);
        client.expectSuccess(client.call("api/v1/reservas/" + reservaId + "/cancelar", body, key, correlationId));
    }

    @Override
    public EstadoReserva leerReserva(UUID reservaId, String correlationId) {
        JsonNode reserva = client.expectSuccess(client.call("api/v1/reservas/" + reservaId, null, null, correlationId));
        return EstadoReserva.valueOf(reserva.path("estado").asText());
    }
}
