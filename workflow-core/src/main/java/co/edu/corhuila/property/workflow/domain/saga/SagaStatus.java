package co.edu.corhuila.property.workflow.domain.saga;

/** The states of a Saga (ADR-009, rule 7). */
public enum SagaStatus {
    RUNNING,
    COMPLETED,
    COMPENSATED,
    /** A compensation failed, or the outcome of a charge or a confirmation cannot be established: a person must decide. */
    FAILED
}
