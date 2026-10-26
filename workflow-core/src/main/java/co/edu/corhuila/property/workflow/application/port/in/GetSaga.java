package co.edu.corhuila.property.workflow.application.port.in;

import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import java.util.UUID;

/** E-24 · GET /sagas/{id}. */
public interface GetSaga {
    /** @throws co.edu.corhuila.property.workflow.domain.saga.UnknownSagaException if it does not exist */
    SagaInstance get(UUID id);
}
