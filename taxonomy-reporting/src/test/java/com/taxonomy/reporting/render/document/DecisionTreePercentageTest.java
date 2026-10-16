package com.taxonomy.reporting.render.document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DecisionTreePercentageTest {
    @ParameterizedTest
    @CsvSource({"en,zero", "de,zero", "en,missing", "de,missing", "en,fraction", "de,fraction",
            "en,tiny", "de,tiny", "en,legacy", "de,legacy"})
    void preservesNumericMeaningWithoutDuplicatingRejectedOrMissingState(String language, String kind) throws Exception {
        DecisionTreePercentageChecks.check(language, kind);
    }
}
