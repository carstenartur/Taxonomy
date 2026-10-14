package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.relations.PartialRelationDistributionChecks;
import com.taxonomy.analysis.cluster.PartialClusterFailureChecks;
import com.taxonomy.analysis.cluster.PartialClusterFlowChecks;
import com.taxonomy.analysis.usecase.PartialArchitectureChecks;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/** Normal Maven/JUnit entry point for the same executable production-boundary checks. */
class PartialAnalysisResilienceTest {
    @TestFactory
    Stream<DynamicTest> partialResultsSurviveFailuresWithoutRepeatingSuccessfulRequests() {
        return Stream.of(PartialAnalysisResilienceChecks.class, PartialArchitectureChecks.class,
                        PartialRelationDistributionChecks.class, PartialClusterFailureChecks.class, PartialClusterFlowChecks.class)
                .flatMap(owner -> Arrays.stream(owner.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
                                && method.getName().startsWith("test") && method.getParameterCount() == 0)
                        .sorted(Comparator.comparing(java.lang.reflect.Method::getName))
                        .map(method -> DynamicTest.dynamicTest(owner.getSimpleName() + ": " + method.getName(), () -> {
                            try { method.invoke(null); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        })));
    }
}
