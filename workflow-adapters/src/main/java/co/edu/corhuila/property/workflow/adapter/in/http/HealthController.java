package co.edu.corhuila.property.workflow.adapter.in.http;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe (workflow-service.yaml, getHealth). Outside /api/, so it needs no
 * token, and the gateway does not route it.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
