package com.taxonomy.analysis.service;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

/** Keeps the executable policy and loopback regressions in the canonical Maven test lifecycle. */
class AnalysisAdmissionPolicyTest {
    @TestFactory Stream<DynamicTest> analysisAdmission() {
        return AnalysisAdmissionQueueChecks.checks().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> entry.getValue().run()));
    }
    @TestFactory Stream<DynamicTest> providerAdmission() {
        return ProviderRequestLimiterChecks.checks().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> entry.getValue().run()));
    }
    @TestFactory Stream<DynamicTest> retryPolicy() {
        return ProviderRetryPolicyChecks.checks().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> entry.getValue().run()));
    }
    @TestFactory Stream<DynamicTest> actualTransportAndRegistry() {
        return AnalysisConcurrencyChecks.checks().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> entry.getValue().run()));
    }
}
