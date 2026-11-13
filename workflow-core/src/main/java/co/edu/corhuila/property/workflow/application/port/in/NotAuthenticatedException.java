package co.edu.corhuila.property.workflow.application.port.in;

/** The token is valid but does not identify a caller this service knows: 401 UNAUTHORIZED. */
public class NotAuthenticatedException extends RuntimeException {
    public NotAuthenticatedException(String message) {
        super(message);
    }
}
