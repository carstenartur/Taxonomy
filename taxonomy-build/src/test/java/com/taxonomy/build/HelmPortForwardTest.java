package com.taxonomy.build;

import org.junit.jupiter.api.Test;

/** No Kubernetes/Helm startup: verifies process ownership in the actual shell functions. */
class HelmPortForwardTest {
    @Test void brokerRestartTerminatesTheOwnedForward() throws Exception {
        HelmPortForwardChecks.check("broker");
    }
    @Test void coordinatorRestartTerminatesTheOwnedForward() throws Exception {
        HelmPortForwardChecks.check("web");
    }
}
