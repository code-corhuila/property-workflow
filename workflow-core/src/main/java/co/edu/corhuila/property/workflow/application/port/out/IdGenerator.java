package co.edu.corhuila.property.workflow.application.port.out;

import java.util.UUID;

public interface IdGenerator {
    UUID newId();
}
