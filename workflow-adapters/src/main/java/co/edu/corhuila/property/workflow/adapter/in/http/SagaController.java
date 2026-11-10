package co.edu.corhuila.property.workflow.adapter.in.http;

import co.edu.corhuila.property.workflow.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.property.workflow.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.workflow.application.port.in.Caller;
import co.edu.corhuila.property.workflow.application.port.in.GetSaga;
import co.edu.corhuila.property.workflow.application.port.in.StartReservarSaga;
import co.edu.corhuila.property.workflow.application.port.in.StartReservarSaga.IniciarReservar;
import co.edu.corhuila.property.workflow.application.port.in.StartReservarSaga.StartResult;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.property.workflow.domain.saga.SagaStep;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E-23 and E-24 (workflow.yaml). Validates the SHAPE of the input; the rules live in the use cases. */
@RestController
@RequestMapping("/api/v1/sagas")
public class SagaController {

    static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final Set<String> METODOS = Set.of("TARJETA_CREDITO", "TARJETA_DEBITO");

    /** No user id and no amount: the first comes from the token, the second from Booking. */
    public record IniciarReservarRequest(String propiedadId, String fechaInicio, String fechaFin, BigDecimal huespedes,
                                         String metodoPago, String tokenPago) {
        /** A record prints its fields: this one never prints the card token. */
        @Override
        public String toString() {
            return "IniciarReservarRequest[propiedadId=" + propiedadId + "]";
        }
    }

    /** SagaResponse: never the internal detail of a failure (ADR-009, rule 9). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SagaResponse(String id, String reservaId, String pagoId, String reembolsoId, String status,
                               List<String> completedSteps, String failedStep, String createdAt, String updatedAt) {
        static SagaResponse of(SagaInstance s) {
            return new SagaResponse(s.id().toString(), str(s.reservaId()), str(s.pagoId()), str(s.reembolsoId()),
                    s.status().name(), s.completedSteps().stream().map(SagaStep::id).toList(),
                    s.status() == SagaStatus.COMPLETED || s.failedStep() == null ? null : s.failedStep().id(),
                    s.createdAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                    s.updatedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }

        private static String str(UUID id) {
            return id == null ? null : id.toString();
        }
    }

    private final StartReservarSaga start;
    private final GetSaga get;

    public SagaController(StartReservarSaga start, GetSaga get) {
        this.start = start;
        this.get = get;
    }

    /** E-23. A reservation that could not be made is still 201: read status (failedStep says where). */
    @PostMapping("/reservar")
    public ResponseEntity<SagaResponse> reservar(@RequestBody IniciarReservarRequest body,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                 HttpServletRequest request) {
        List<FieldError> problems = new ArrayList<>();
        UUID propiedadId = null;
        if (body.propiedadId() == null || !UUID_FORMAT.matcher(body.propiedadId()).matches()) {
            problems.add(new FieldError("propiedadId", "must be a UUID"));
        } else {
            propiedadId = UUID.fromString(body.propiedadId());
        }
        LocalDate inicio = date(body.fechaInicio(), "fechaInicio", problems);
        LocalDate fin = date(body.fechaFin(), "fechaFin", problems);
        if (inicio != null && fin != null && !fin.isAfter(inicio)) {
            problems.add(new FieldError("fechaFin", "must be after fechaInicio"));
        }
        BigDecimal huespedes = body.huespedes();
        if (huespedes == null || huespedes.stripTrailingZeros().scale() > 0
                || huespedes.compareTo(BigDecimal.ONE) < 0 || huespedes.compareTo(BigDecimal.valueOf(20)) > 0) {
            problems.add(new FieldError("huespedes", "must be an integer from 1 to 20"));
        }
        if (!METODOS.contains(body.metodoPago())) {
            problems.add(new FieldError("metodoPago", "must be TARJETA_CREDITO or TARJETA_DEBITO"));
        }
        if (body.tokenPago() == null || body.tokenPago().isBlank() || body.tokenPago().length() > 255) {
            problems.add(new FieldError("tokenPago", "required: the card token of the gateway"));
        }
        if (key == null || key.length() < 8 || key.length() > 128) {
            problems.add(new FieldError("Idempotency-Key", "header required, 8 to 128 characters"));
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("the request has invalid fields", problems);
        }
        StartResult result = start.start(caller(request), new IniciarReservar(null, propiedadId, inicio, fin,
                huespedes.intValueExact(), body.metodoPago(), body.tokenPago(), key, MDC.get(CorrelationFilter.MDC_KEY)));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)   // 200: the same Saga
                .header("Location", "/api/v1/sagas/" + result.saga().id())
                .body(SagaResponse.of(result.saga()));
    }

    /** E-24. */
    @GetMapping("/{id}")
    public SagaResponse saga(@PathVariable String id, HttpServletRequest request) {
        if (!UUID_FORMAT.matcher(id).matches()) {
            throw new ValidationException("the id must be a UUID", List.of(new FieldError("id", "must be a UUID")));
        }
        return SagaResponse.of(get.get(caller(request), UUID.fromString(id)));
    }

    /** The caller, from the claims AuthFilter verified. */
    static Caller caller(HttpServletRequest request) {
        JsonNode claims = (JsonNode) request.getAttribute(AuthFilter.CLAIMS_ATTRIBUTE);
        List<String> roles = new ArrayList<>();
        claims.path("roles").forEach(r -> roles.add(r.asText()));
        return new Caller(claims.path("sub").asText(), roles, "service".equals(claims.path("scope").asText()));
    }

    private static LocalDate date(String value, String field, List<FieldError> problems) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            problems.add(new FieldError(field, "must be a date, YYYY-MM-DD"));
            return null;
        }
    }
}
