package co.edu.corhuila.property.workflow.app;

import co.edu.corhuila.property.workflow.adapter.in.http.AuthFilter;
import co.edu.corhuila.property.workflow.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.property.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.property.workflow.adapter.out.participants.HttpBooking;
import co.edu.corhuila.property.workflow.adapter.out.participants.HttpPayment;
import co.edu.corhuila.property.workflow.adapter.out.participants.ParticipantClient;
import co.edu.corhuila.property.workflow.adapter.out.persistence.InMemorySagaStore;
import co.edu.corhuila.property.workflow.adapter.out.persistence.JdbcSagaStore;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Booking;
import co.edu.corhuila.property.workflow.application.port.out.Participants.Payment;
import co.edu.corhuila.property.workflow.application.port.out.SagaStore;
import co.edu.corhuila.property.workflow.application.usecase.RunReservarSaga;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Composition root: the only place that knows every concrete type. The filters
 * are registered here explicitly, in order: correlation first, so that even a
 * 401 carries its traceId.
 */
@Configuration
public class WorkflowConfiguration {

    /**
     * PERSISTENCE=postgres (the default) needs DATABASE_URL, DATABASE_USER (workflow_app
     * in the platform) and DATABASE_PASSWORD: without them the workflow does not start.
     * PERSISTENCE=memory is for tests and local development only (norm 5.5.2).
     */
    @Bean
    Storage storage(@Value("${workflow.persistence:postgres}") String persistence,
                    @Value("${workflow.database.url:}") String url,
                    @Value("${workflow.database.user:}") String user,
                    @Value("${workflow.database.password:}") String password,
                    @Value("${workflow.database.pool-max:10}") int poolMax,
                    @Value("${workflow.database.statement-timeout-ms:5000}") int statementTimeoutMs) {
        return Storage.connect(persistence, url, user, password, poolMax, statementTimeoutMs);
    }

    /**
     * The workflow's own credential towards the participants: SERVICE_TOKEN, the token of
     * svc-workflow (scope service, D-C14). Without it the workflow does not start. Every
     * limit is configuration with a default: 10 s per call (longer than Payment's 8 s for
     * a charge), 2 attempts, backoff from 200 ms.
     */
    @Bean
    ParticipantClient.Limits participantLimits(@Value("${workflow.participants.timeout-ms}") long timeoutMs,
                                               @Value("${workflow.participants.max-attempts}") int maxAttempts,
                                               @Value("${workflow.participants.backoff-ms}") long backoffMs) {
        return new ParticipantClient.Limits(Duration.ofMillis(timeoutMs), maxAttempts, Duration.ofMillis(backoffMs));
    }

    @Bean
    Booking booking(@Value("${workflow.participants.booking-url}") String url,
                    @Value("${workflow.participants.service-token:}") String token, ParticipantClient.Limits limits) {
        return new HttpBooking(new ParticipantClient("booking", url, token, limits));
    }

    @Bean
    Payment payment(@Value("${workflow.participants.payment-url}") String url,
                    @Value("${workflow.participants.service-token:}") String token, ParticipantClient.Limits limits) {
        return new HttpPayment(new ParticipantClient("payment", url, token, limits));
    }

    /**
     * The orchestrator of the Saga reservar (D3b). REQUEST_TIMEOUT_MS (25 s by default) is
     * the limit of the request that starts a Saga: the three steps with their retries fit
     * in it, and it is shorter than the time-out of the API Gateway.
     */
    @Bean
    RunReservarSaga runReservarSaga(Booking booking, Payment payment, SagaStore store,
                                    @Value("${workflow.request-timeout-ms}") long requestTimeoutMs) {
        return new RunReservarSaga(booking, payment, store, UUID::randomUUID, Clock.systemUTC(),
                Duration.ofMillis(requestTimeoutMs));
    }

    @Bean
    SagaStore sagaStore(Storage storage) {
        return storage.inMemory() ? new InMemorySagaStore() : new JdbcSagaStore(storage.jdbc());
    }

    /**
     * JWT_PUBLIC_KEY (the PEM itself; a one-line value with literal \n escapes, as
     * a .env file holds it, is accepted) or JWT_PUBLIC_KEY_FILE. Without a key
     * the service does not start.
     */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem,
                                @Value("${JWT_PUBLIC_KEY_FILE:}") String file) throws IOException {
        String key = !pem.isBlank() ? pem.replace("\\n", "\n")
                : !file.isBlank() ? Files.readString(Path.of(file)) : "";
        return new Rs256Verifier(key);
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
