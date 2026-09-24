package com.taxonomy.analysis.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class IndependentProductScoreBoundaryTest {
    static Stream<String> invalidNumbers() { return IndependentProductScoreChecks.INVALID.stream(); }
    static Stream<String> validNumbers() { return IndependentProductScoreChecks.VALID.stream(); }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void rejectsInvalidScoresBeforeThresholding(String number) throws Exception {
        IndependentProductScoreChecks.rejects(number);
    }

    @ParameterizedTest
    @MethodSource("validNumbers")
    void preservesExactScoresReasonsAndIndependentThreshold(String number) throws Exception {
        IndependentProductScoreChecks.accepts(number);
    }
}
