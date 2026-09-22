package com.taxonomy.export;

import org.junit.jupiter.api.Test;

class SparxDiagramHandoffTest {
    @Test
    void invalidGeneratedVisioPartsRemainDiagnosableServerFailures() {
        VisioFailureClassificationChecks.run();
    }

    @Test
    void currentGraphProducesExplicitFreshCopyWithoutLosingIdentityOrRelations() throws Exception {
        SparxDiagramHandoffChecks.run();
    }
}
