package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisReasonLengthTest {

    @Test
    void exposesStableWireValues() {
        assertEquals("short", AnalysisReasonLength.SHORT.value());
        assertEquals("standard", AnalysisReasonLength.STANDARD.value());
        assertEquals("detailed", AnalysisReasonLength.DETAILED.value());
        for (var length : AnalysisReasonLength.values()) {
            assertEquals(length, AnalysisReasonLength.fromValue(length.value()));
        }
    }

    @Test
    void missingLegacyValueRetainsStandardBehavior() {
        assertEquals(AnalysisReasonLength.STANDARD, AnalysisReasonLength.fromValue(null));
        assertEquals(AnalysisReasonLength.STANDARD, AnalysisReasonLength.fromValue(""));
        assertEquals(AnalysisReasonLength.STANDARD, AnalysisReasonLength.fromValue(" \t"));
    }

    @Test
    void recordedValuesPreserveExplicitChoicesAndUnknownProvenance() {
        assertNull(AnalysisReasonLength.fromRecordedValue(null));
        for (var length : AnalysisReasonLength.values()) {
            assertEquals(length, AnalysisReasonLength.fromRecordedValue(length.value()));
        }
    }

    @Test
    void blankRecordedValuesAreRejectedWithoutChangingExecutionFallback() {
        for (String blank : new String[]{"", " ", "\t", "\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnalysisReasonLength.fromRecordedValue(blank));
            assertEquals(AnalysisReasonLength.STANDARD, AnalysisReasonLength.fromValue(blank));
        }
    }

    @Test
    void unknownNonblankValuesAreRejected() {
        for (String invalid : new String[]{"long", "DETAILED", " detailed ", "1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AnalysisReasonLength.fromValue(invalid));
        }
    }
    @Test
    void analysisLanguageHasStrictWireValuesAndOnlyNewAdmissionUsesLocaleFallback() {
        assertNull(AnalysisLanguage.fromValue(null));
        assertEquals(AnalysisLanguage.GERMAN, AnalysisLanguage.fromValue("de"));
        assertEquals(AnalysisLanguage.ENGLISH, AnalysisLanguage.fromValue("en"));
        assertEquals("de", AnalysisLanguage.GERMAN.value());
        assertEquals("en", AnalysisLanguage.ENGLISH.value());
        assertEquals(AnalysisLanguage.GERMAN, AnalysisLanguage.fromLocale(java.util.Locale.forLanguageTag("de-CH")));
        assertEquals(AnalysisLanguage.ENGLISH, AnalysisLanguage.fromLocale(java.util.Locale.FRENCH));
        assertEquals(AnalysisLanguage.ENGLISH, AnalysisLanguage.fromLocale(null));
        for (String invalid : new String[]{"", " ", "DE", "de-DE", "fr", "ui"})
            assertThrows(IllegalArgumentException.class, () -> AnalysisLanguage.fromValue(invalid));
        assertNull(new AnalysisRequest().getAnalysisLanguage());
        assertNull(new AnalysisResult().getAnalysisLanguage());
        assertNull(new SavedAnalysis().getAnalysisLanguage());
    }

}
