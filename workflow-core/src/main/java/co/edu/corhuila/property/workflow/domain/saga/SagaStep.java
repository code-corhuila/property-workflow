package co.edu.corhuila.property.workflow.domain.saga;

/** The three steps of the Saga reservar, in order (workflow-endpoint-fiches.md). */
public enum SagaStep {
    CREAR_RESERVA("crear-reserva"),
    COBRAR("cobrar"),
    /** The last step: it has no compensation. */
    CONFIRMAR_RESERVA("confirmar-reserva");

    private final String id;

    SagaStep(String id) {
        this.id = id;
    }

    /** The step id of the contract: completedSteps, failedStep and the keys use it. */
    public String id() {
        return id;
    }
}
