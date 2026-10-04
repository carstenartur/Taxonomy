package com.taxonomy.composition.analysis.artemis;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

class ArtemisAnalysisTlsTest {
    @TestFactory
    Stream<DynamicTest> validatesEveryConnectorAndExactTlsOption() {
        return ArtemisTlsCases.cases().stream().map(test ->
                DynamicTest.dynamicTest(test.name(), () -> ArtemisTlsCases.verify(test)));
    }
}
