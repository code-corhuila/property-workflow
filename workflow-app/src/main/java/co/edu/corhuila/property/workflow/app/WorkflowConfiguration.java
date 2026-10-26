package co.edu.corhuila.property.workflow.app;

import co.edu.corhuila.property.workflow.adapter.in.http.AuthFilter;
import co.edu.corhuila.property.workflow.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.property.workflow.adapter.in.http.Rs256Verifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
