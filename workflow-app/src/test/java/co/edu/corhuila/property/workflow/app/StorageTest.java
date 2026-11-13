package co.edu.corhuila.property.workflow.app;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Norm 5.5.2: memory is never a default. Without its database the service does not start. */
class StorageTest {

    @Test
    void withoutDatabaseUrlTheServiceDoesNotStartAndSaysWhy() {
        var e = assertThrows(IllegalStateException.class, () -> Storage.connect("postgres", "", "workflow_app", "x", 10, 5000));
        assertTrue(e.getMessage().contains("DATABASE_URL is required"), e.getMessage());
    }

    @Test
    void anUnknownPersistenceIsRefused() {
        assertThrows(IllegalStateException.class, () -> Storage.connect("h2", "jdbc:h2:mem:x", "", "", 10, 5000));
    }

    @Test
    void memoryOnlyWhenAskedFor() {
        try (Storage storage = Storage.connect("memory", "", "", "", 10, 5000)) {
            assertTrue(storage.inMemory());
        }
    }
}
