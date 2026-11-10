package co.edu.corhuila.property.workflow.application.port.in;

import co.edu.corhuila.property.workflow.domain.saga.SagaInstance;
import java.util.UUID;

/** E-24 · GET /sagas/{id}. */
public interface GetSaga {
    /** @throws co.edu.corhuila.property.workflow.domain.saga.UnknownSagaException if it does not exist */
    SagaInstance get(UUID id);

    /**
     * E-24 for the caller: the guest who started the Saga or an ADMIN (security #182); a
     * service credential gets 403 (D-C14), another guest 403, an unknown id 404.
     */
    SagaInstance get(Caller caller, UUID id);
}
