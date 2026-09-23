package com.taxonomy.analysis.relations;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.stream.Stream;

/** Normal JUnit discovery of the same production-boundary executable contracts. */
class RelationSearchEngineTest {
    @TestFactory
    Stream<DynamicTest> contracts() {
        return Arrays.stream(RelationSearchContract.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("test"))
                .map(method -> DynamicTest.dynamicTest(method.getName(), () -> {
                    try { method.invoke(null); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }
}
