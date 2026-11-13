package co.edu.corhuila.property.workflow.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Who calls, as the verified token says: a person (claim roles) or a service (claim
 * scope: service, D-C14). The inbound adapter builds it; the use case decides the
 * permission (security-rules.md A01: in the use case, never in the controller).
 */
public record Caller(String subject, List<String> roles, boolean service) {

    public Caller {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean isAdmin() {
        return !service && roles.contains("ADMIN");
    }

    /** "Service credential only": a person's token gets 403, whatever its role (D-C14). */
    public void requireService() {
        if (!service) {
            throw new AccessDeniedException("this operation is reserved to a service credential");
        }
    }

    /**
     * The guest id of a person. A token with neither roles nor scope has no permission
     * (403); a person's token whose subject is not an id is not a valid credential (401).
     */
    public UUID personId() {
        if (service || roles.isEmpty()) {
            throw new AccessDeniedException("this credential is not allowed to perform the operation");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new NotAuthenticatedException("the token does not name a person");
        }
    }
}
