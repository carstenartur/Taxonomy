package com.taxonomy.analysis.service;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

class ProviderPermitLifecycleTest {
    @TestFactory
    Stream<DynamicTest> physicalAttemptCapacityLifecycle() {
        return ProviderPermitLifecycleCases.checks().stream()
                .map(check -> DynamicTest.dynamicTest(check.name(), check.action()::run));
    }
}
