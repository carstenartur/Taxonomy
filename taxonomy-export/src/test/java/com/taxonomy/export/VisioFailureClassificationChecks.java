package com.taxonomy.export;

import java.io.UncheckedIOException;
import java.util.Map;

public final class VisioFailureClassificationChecks {
    private VisioFailureClassificationChecks() { }
    public static void run() {
        try { VisioOpcValidator.validate(Map.of()); }
        catch (RuntimeException failure) {
            if (!(failure instanceof UncheckedIOException))
                throw new AssertionError("Generated-package failure must be a server export failure, not invalid user input", failure);
            if (!failure.getMessage().contains("VISIO_PACKAGE_VALIDATION_FAILED") || failure.getCause() == null || failure.getCause().getCause() == null)
                throw new AssertionError("Stable diagnosis and the original validation cause must survive", failure);
            return;
        }
        throw new AssertionError("Invalid generated package accepted");
    }
    public static void main(String[] args) { run(); System.out.println("VISIO_FAILURE_CLASSIFICATION_OK"); }
}
