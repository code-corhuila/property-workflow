package co.edu.corhuila.property.workflow.app;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The contract checks of Annex C that need no business endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowHttpTest {

    static final KeyPair KEYS = generate();
    static final KeyPair OTHER_KEYS = generate();
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        registry.add("JWT_PUBLIC_KEY", () -> pem);
    }

    @Test
    void healthAnswersWithoutAToken() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void aProtectedRouteWithoutATokenIs401WithTheEnvelope() throws Exception {
        mvc.perform(get("/api/v1/sagas").header("X-Correlation-Id", "trace-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.traceId", is("trace-401")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alg-none", "alg-hs256", "expired", "tampered", "other-key", "malformed"})
    void rejectsEveryInvalidToken(String kind) throws Exception {
        String token = switch (kind) {
            case "alg-none" -> token("none", 3600, KEYS.getPrivate());
            case "alg-hs256" -> token("HS256", 3600, KEYS.getPrivate());
            case "expired" -> token("RS256", -120, KEYS.getPrivate());
            case "tampered" -> token("RS256", 3600, KEYS.getPrivate()) + "x";
            case "other-key" -> token("RS256", 3600, OTHER_KEYS.getPrivate());
            default -> "not-a-jwt";
        };
        mvc.perform(get("/api/v1/sagas").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
    }

    @Test
    void anUnknownRouteIs404WithTheEnvelopeAndTheSameCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/no-such-route").header("Authorization", bearer())
                        .header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")))
                .andExpect(jsonPath("$.traceId", is("trace-abc-123")))
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"));
    }

    @Test
    void withoutACorrelationIdTheServiceGeneratesOneAndUsesItAsTraceId() throws Exception {
        var response = mvc.perform(get("/api/v1/no-such-route").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)))
                .andReturn().getResponse();
        mvc.perform(get("/api/v1/no-such-route").header("Authorization", bearer())
                        .header("X-Correlation-Id", response.getHeader("X-Correlation-Id")))
                .andExpect(jsonPath("$.traceId", is(response.getHeader("X-Correlation-Id"))));
    }

    private static String bearer() throws Exception {
        return "Bearer " + token("RS256", 3600, KEYS.getPrivate());
    }

    private static String token(String alg, long expiresIn, PrivateKey key) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"svc-workflow\",\"scope\":\"service\",\"exp\":"
                + (Instant.now().getEpochSecond() + expiresIn) + "}").getBytes(StandardCharsets.UTF_8));
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(key);
        rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + enc.encodeToString(rsa.sign());
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
