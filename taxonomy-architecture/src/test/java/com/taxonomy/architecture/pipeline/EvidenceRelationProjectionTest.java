package com.taxonomy.architecture.pipeline;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.stream.Stream;

class EvidenceRelationProjectionTest {
    @TestFactory
    Stream<DynamicTest> contracts() {
        return Stream.of(EvidenceRelationProjectionContract.class, SourceContributionProjectionContract.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getName().startsWith("test"))
                .map(method -> DynamicTest.dynamicTest(method.getDeclaringClass().getSimpleName() + "." + method.getName(), () -> {
                    try { method.invoke(null); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }
}
