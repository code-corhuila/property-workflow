package co.edu.corhuila.property.workflow.adapter.out.persistence;

import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.property.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.property.workflow.domain.saga.SagaStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * workflow.saga_instance (06-data/models.md, ADR-010), as workflow_app. The row is
 * updated after every step and every compensation; it holds no business data, only the
 * identifiers the participants returned.
 */
public class JdbcSagaStore implements SagaStore {

    private static final String COLUMNS = "id, guest_id, idempotency_key, correlation_id, status, completed_steps, "
            + "failed_step, error_detail, reservation_id, payment_id, refund_id, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcSagaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** uq_saga_instance_idempotency_key decides: of two starts with the same key, one row. */
    @Override
    public CreateResult create(SagaInstance s) {
        int inserted = jdbc.update("INSERT INTO workflow.saga_instance (id, saga_type, status, idempotency_key, guest_id, "
                        + "correlation_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING",
                s.id(), SagaInstance.TYPE, s.status().name(), s.idempotencyKey(), s.guestId(), s.correlationId(),
                utc(s.createdAt()), utc(s.updatedAt()));
        if (inserted == 1) {
            return new CreateResult(s, true);
        }
        return new CreateResult(jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga_instance WHERE idempotency_key = ?",
                (rs, n) -> map(rs), s.idempotencyKey()).get(0), false);
    }

    @Override
    public void save(SagaInstance s) {
        jdbc.update("UPDATE workflow.saga_instance SET status = ?, completed_steps = ?::text[], failed_step = ?, "
                        + "error_detail = ?, reservation_id = ?, payment_id = ?, refund_id = ?, updated_at = ? WHERE id = ?",
                s.status().name(), steps(s.completedSteps()), s.failedStep() == null ? null : s.failedStep().id(),
                s.errorDetail(), s.reservaId(), s.pagoId(), s.reembolsoId(), utc(s.updatedAt()), s.id());
    }

    @Override
    public Optional<SagaInstance> get(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga_instance WHERE id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    /** idx_saga_instance_open serves it: the open Sagas are few. */
    @Override
    public List<SagaInstance> findStale(Instant updatedBefore, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow.saga_instance WHERE status = 'RUNNING' AND updated_at < ? "
                + "ORDER BY updated_at LIMIT ?", (rs, n) -> map(rs), utc(updatedBefore), limit);
    }

    /** The step ids are fixed words, so the array literal needs no escaping: {crear-reserva,cobrar}. */
    private static String steps(List<SagaStep> steps) {
        return steps.stream().map(SagaStep::id).collect(Collectors.joining(",", "{", "}"));
    }

    private static SagaInstance map(ResultSet rs) throws SQLException {
        String failed = rs.getString("failed_step");
        List<SagaStep> completed = Arrays.stream((String[]) rs.getArray("completed_steps").getArray())
                .map(SagaStep::fromId).toList();
        return SagaInstance.restore(rs.getObject("id", UUID.class), rs.getObject("guest_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("correlation_id"), SagaStatus.valueOf(rs.getString("status")),
                completed, failed == null ? null : SagaStep.fromId(failed), rs.getString("error_detail"),
                rs.getObject("reservation_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("refund_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
