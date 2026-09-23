package com.taxonomy.architecture.pipeline;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.stream.Stream;

class EvidenceRelationProjectionTest {
    @TestFactory
    Stream<DynamicTest> contracts() {
        return Arrays.stream(EvidenceRelationProjectionContract.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("test"))
                .map(method -> DynamicTest.dynamicTest(method.getName(), () -> {
                    try { method.invoke(null); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }
}
