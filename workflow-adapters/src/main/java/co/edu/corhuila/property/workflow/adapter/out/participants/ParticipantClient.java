package co.edu.corhuila.property.workflow.adapter.out.participants;

import co.edu.corhuila.property.workflow.application.port.out.Participants.StepRejectedException;
import co.edu.corhuila.property.workflow.application.port.out.Participants.StepUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One HTTP call of the Saga to a participant (workflow-endpoint-fiches.md). It carries
 * the workflow's own service token (never the guest's, ADR-009 rule 8), the
 * X-Correlation-Id of the Saga and, on a step or a compensation, its Idempotency-Key.
 * Retries are bounded, with exponential backoff and jitter, and only for network
 * errors, 429 and 5xx; a 4xx is never retried. Nothing of a body is logged.
 */
public class ParticipantClient {

    /** Settings, all from configuration with a default (application.yml). */
    public record Limits(Duration perCall, int maxAttempts, Duration backoff) { }

    /** The status and body of an answer below 500 other than 429. */
    public record Answer(int status, JsonNode body) { }

    private final String name;
    private final URI baseUrl;
    private final String serviceToken;
    private final Limits limits;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ParticipantClient(String name, String baseUrl, String serviceToken, Limits limits) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalStateException("SERVICE_TOKEN is required: the token of svc-workflow, issued by "
                    + "property-identity-api with --issue-service-token (HU-IDENT-004), never versioned");
        }
        this.name = name;
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.serviceToken = serviceToken.strip();
        this.limits = limits;
        this.http = HttpClient.newBuilder().connectTimeout(limits.perCall())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * POST (body not null) or GET. Returns any answer below 500 other than 429, for the
     * caller to read.
     *
     * @throws StepUnavailableException no answer, 429 or 5xx after the attempts: the outcome is not known
     */
    public Answer call(String path, Object body, String idempotencyKey, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(limits.perCall())
                .header("Authorization", "Bearer " + serviceToken).header("Accept", "application/json")
                .header("X-Correlation-Id", correlationId);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        try {
            request = body == null ? request.GET() : request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        } catch (IOException e) {
            throw new IllegalStateException("the body cannot be written", e);
        }
        HttpRequest built = request.build();
        String last = "no attempt";
        for (int attempt = 1; attempt <= limits.maxAttempts(); attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            try {
                HttpResponse<String> response = http.send(built, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 500 && status != 429) {
                    return new Answer(status, read(response.body()));
                }
                last = name + " answered " + status;
            } catch (IOException e) {
                last = name + " did not answer: " + e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepUnavailableException(name + " call interrupted");
            }
        }
        throw new StepUnavailableException(last);
    }

    /** A 2xx, or {@link StepRejectedException} with the code of the envelope: the participant did not apply it. */
    public JsonNode expectSuccess(Answer answer) {
        if (answer.status() >= 200 && answer.status() < 300) {
            return answer.body();
        }
        throw new StepRejectedException(answer.status(), name + " answered " + answer.status() + " "
                + answer.body().path("error").asText("") + ": " + answer.body().path("message").asText(""));
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    private void pause(int attempt) {
        long base = limits.backoff().toMillis() << (attempt - 2);
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(limits.backoff().toMillis() + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
