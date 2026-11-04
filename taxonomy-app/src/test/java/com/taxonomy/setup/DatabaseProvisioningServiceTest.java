package com.taxonomy.setup;

import org.junit.jupiter.api.Test;
import static com.taxonomy.setup.DatabaseProvisioningService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseProvisioningServiceTest {
    @Test void completedSequentialRequestsDoNotLeaveCapacityTemporarilyBusy() {
        var provider = new Provider() {
            public String type() { return "postgres"; }
            public void validate(ProvisionSettings input) { }
            public ProvisionResult execute(ProvisionSettings input, boolean create, Progress progress) {
                return result(Outcome.READY, ResourceState.CREATE, ResourceState.CREATE, "ready", "Plan");
            }
        };
        var service = new DatabaseProvisioningService(provider);
        var input = new ProvisionSettings(new DatabaseSetupService.ConnectionSettings("postgres", "db", 5432,
                "taxonomy", "taxonomy_app", "application-password", false, ""),
                "postgres", "bootstrap", "administrator-password", false, null, null);
        for (int iteration = 0; iteration < 1000; iteration++) {
            assertEquals(Outcome.READY, service.inspect(input).outcome(),
                    "A fully completed request must release its capacity before publishing its result");
        }
    }
}
