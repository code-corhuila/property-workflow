package co.edu.corhuila.property.workflow.adapter.in.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Each service validates the token itself (norm 5.3.7, 07-api/guidelines.md D-C11).
 * The gateway only filters requests that carry no credentials; a service that
 * trusted a header set upstream would accept anyone who reaches it through the
 * internal network.
 */
public class Rs256Verifier {

    public static class InvalidTokenException extends Exception {
        InvalidTokenException(String message) {
            super(message);
        }
    }

    private static final long LEEWAY_SECONDS = 30;

    private final PublicKey key;
    private final ObjectMapper json = new ObjectMapper();

    public Rs256Verifier(String publicKeyPem) {
        if (publicKeyPem == null || !publicKeyPem.contains("BEGIN PUBLIC KEY")) {
            throw new IllegalArgumentException("JWT public key is not a PEM public key");
        }
        String base64 = publicKeyPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("JWT public key cannot be read", e);
        }
    }

    /** Returns the claims of a valid token: a person's token or a service token. */
    public JsonNode verify(String token, Instant now) throws InvalidTokenException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }
        try {
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            // A closed rule: a token that declares "none" or HS256 is rejected
            // instead of being checked with the wrong algorithm.
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new InvalidTokenException("algorithm not allowed");
            }
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(key);
            rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidTokenException("bad signature");
            }
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!claims.path("exp").isNumber() || !claims.path("sub").isTextual()) {
                throw new InvalidTokenException("exp and sub are required");
            }
            if (now.getEpochSecond() > claims.get("exp").asLong() + LEEWAY_SECONDS) {
                throw new InvalidTokenException("expired");
            }
            return claims;
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException("unreadable token");
        }
    }
}
