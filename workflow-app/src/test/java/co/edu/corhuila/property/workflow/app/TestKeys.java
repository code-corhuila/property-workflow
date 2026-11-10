package co.edu.corhuila.property.workflow.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One key pair for the HTTP tests: the service gets the public key, the tests sign tokens with the private one. */
final class TestKeys {

    static final KeyPair KEYS = generate();

    private TestKeys() { }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("JWT_PUBLIC_KEY", () -> pem("PUBLIC", KEYS.getPublic().getEncoded()));
    }

    /** A bearer header with these claims (JSON members, without braces) and one hour of life. */
    static String bearer(String claims) {
        try {
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = enc.encodeToString(("{" + claims + ",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}")
                    .getBytes(StandardCharsets.UTF_8));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(KEYS.getPrivate());
            rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return "Bearer " + header + "." + payload + "." + enc.encodeToString(rsa.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String pem(String kind, byte[] der) {
        return "-----BEGIN " + kind + " KEY-----\n" + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + kind + " KEY-----\n";
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
