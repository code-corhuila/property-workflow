package co.edu.corhuila.property.workflow.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.property.workflow.domain.saga.UnknownSagaException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The errors of the core become status codes with the envelope, in this one place. */
class ErrorHandlerTest {

    final ErrorHandler handler = new ErrorHandler();

    @BeforeEach
    void correlation() {
        MDC.put(CorrelationFilter.MDC_KEY, "trace-404");
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void anUnknownSagaIs404NotFound() {
        var response = handler.unknownSaga(new UnknownSagaException(UUID.randomUUID()));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(ApiError.NOT_FOUND, response.getBody().error());
        assertEquals("trace-404", response.getBody().traceId());
    }

    @Test
    void anUnexpectedErrorIs500WithANeutralMessage() {
        var response = handler.unexpected(new IllegalStateException("connection refused to booking-api:3001"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(ApiError.INTERNAL_ERROR, response.getBody().error());
        assertEquals("internal server error", response.getBody().message());
    }
}
