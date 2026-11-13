package co.edu.corhuila.property.workflow.domain.saga;

import java.util.UUID;

public class UnknownSagaException extends RuntimeException {
    public UnknownSagaException(UUID id) {
        super("saga " + id + " not found");
    }
}
