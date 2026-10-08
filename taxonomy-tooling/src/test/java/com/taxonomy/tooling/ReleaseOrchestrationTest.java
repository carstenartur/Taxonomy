package com.taxonomy.tooling;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Maven/JUnit is the normal execution authority for these boundary regressions. */
class ReleaseOrchestrationTest {
    @TestFactory
    Stream<DynamicTest> releaseHandoffChecks() {
        return ReleaseOrchestrationChecks.cases(ReleaseOrchestrationChecks.repositoryRoot())
                .stream().map(c -> DynamicTest.dynamicTest(c.name(), c.body()::run));
    }
}
