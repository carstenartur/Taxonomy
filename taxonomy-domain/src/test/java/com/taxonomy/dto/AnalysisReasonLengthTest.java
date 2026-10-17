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
}
