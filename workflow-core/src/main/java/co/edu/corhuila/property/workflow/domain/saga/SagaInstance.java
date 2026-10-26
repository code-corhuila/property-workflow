package co.edu.corhuila.property.workflow.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The persisted state of one Saga reservar (06-data/models.md, workflow.saga_instance).
 * It holds no business data: only the identifiers the participants returned.
 */
public final class SagaInstance {

    public static final String TYPE = "reservar";

    private final UUID id;
    private final UUID guestId;
    private final String idempotencyKey;
    private final String correlationId;
    private final List<SagaStep> completedSteps = new ArrayList<>();
    private SagaStatus status = SagaStatus.RUNNING;
    private SagaStep failedStep;
    private String errorDetail;          // internal: never returned by the API
    private UUID reservaId;
    private UUID pagoId;
    private UUID reembolsoId;
    private final Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;

    public SagaInstance(UUID id, UUID guestId, String idempotencyKey, String correlationId) {
        this.id = id;
        this.guestId = guestId;
        this.idempotencyKey = idempotencyKey;
        this.correlationId = correlationId;
    }

    /** Idempotency-Key of a step: a retried step never creates nor charges twice. */
    public String keyOf(SagaStep step) {
        return id + ":" + step.id();
    }

    /** Idempotency-Key of the compensation of a step. */
    public String compensationKeyOf(SagaStep step) {
        return keyOf(step) + ":compensar";
    }

    public void stepDone(SagaStep step) {
        completedSteps.add(step);
        touch();
    }

    public void stepFailed(SagaStep step, String detail) {
        failedStep = step;
        addDetail(step.id() + ": " + detail);
    }

    public void complete() {
        failedStep = null;
        status = SagaStatus.COMPLETED;
        touch();
    }

    public void compensated() {
        status = SagaStatus.COMPENSATED;
        touch();
    }

    public void fail(String detail) {
        addDetail(detail);
        status = SagaStatus.FAILED;
    }

    public void reservaCreada(UUID id) { reservaId = id; touch(); }
    public void pagoRegistrado(UUID id) { pagoId = id; touch(); }
    public void reembolsoRegistrado(UUID id) { reembolsoId = id; touch(); }

    private void addDetail(String detail) {
        errorDetail = errorDetail == null ? detail : errorDetail + "; " + detail;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID guestId() { return guestId; }
    public String idempotencyKey() { return idempotencyKey; }
    public String correlationId() { return correlationId; }
    public List<SagaStep> completedSteps() { return List.copyOf(completedSteps); }
    public SagaStatus status() { return status; }
    public SagaStep failedStep() { return failedStep; }
    public String errorDetail() { return errorDetail; }
    public UUID reservaId() { return reservaId; }
    public UUID pagoId() { return pagoId; }
    public UUID reembolsoId() { return reembolsoId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
