package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.io.InterruptedIOException;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSinkTest {
    @Test void defaultCheckpointObservesCancellationBeforeAnyEntryAndPreservesTheSignal() {
        ComponentSink sink = (path, input) -> fail("Preflight checkpoints must not write entries");
        assertDoesNotThrow(sink::checkpoint);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, sink::checkpoint);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
