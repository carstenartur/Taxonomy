package com.taxonomy;

import org.junit.jupiter.api.Test;

/**
 * Keeps the module-gate owner/selector contract active in the ordinary application
 * test suite used by canonical {@code -Pci} verification. The focused
 * {@code architecture-tests} profile has its separate fixed selector anchor.
 */
class ArchitectureModuleGateReachabilityRegressionTest {

    @Test
    void canonicalVerificationAlsoChecksModuleGateOwnerAndSelectors() throws Exception {
        new ArchitectureExceptionLedgerTest().moduleChecksRemainReachableFromTheArchitectureProfile();
    }
}
