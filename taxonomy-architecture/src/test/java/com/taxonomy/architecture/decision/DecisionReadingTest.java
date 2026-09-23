package com.taxonomy.architecture.decision;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecisionReadingTest {
    @ParameterizedTest
    @ValueSource(strings = {"de", "en"})
    void reportNavigationAndMeasuredDurationRemainReadable(String locale) throws Exception {
        DecisionReadingChecks.contents(locale);
    }
}
