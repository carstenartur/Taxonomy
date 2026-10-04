package com.taxonomy.analysis.dispatch;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

class AnalysisDispatchRecoveryHandoffTest {
    @TestFactory
    Stream<DynamicTest> queuedRecoveryEventsCannotBeStrandedByTheCurrentOwner() {
        return DispatchRecoveryCases.cases().stream().map(test ->
                DynamicTest.dynamicTest(test.name(), () -> DispatchRecoveryCases.verify(test)));
    }
}
