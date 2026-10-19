package com.taxonomy.composition.analysis.artemis;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.util.stream.Stream;

class ArtemisPermitTransactionTest {
    @TestFactory
    Stream<DynamicTest> durableCapacityFaults() {
        return ArtemisPermitTransactionCases.checks().stream()
                .map(check -> DynamicTest.dynamicTest(check.name(), check.action()::run));
    }
}
