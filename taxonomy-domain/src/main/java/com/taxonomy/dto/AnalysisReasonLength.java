package com.taxonomy.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Requested extent of the decision explanations generated during an analysis. */
public enum AnalysisReasonLength {
    SHORT("short"),
    STANDARD("standard"),
    DETAILED("detailed");

    private final String value;

    AnalysisReasonLength(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** Resolve legacy execution/configuration values to the existing standard behavior. */
    public static AnalysisReasonLength fromValue(String value) {
        if (value == null || value.isBlank()) return STANDARD;
        for (var length : values()) {
            if (length.value.equals(value)) return length;
        }
        throw new IllegalArgumentException("Analysis reason length must be short, standard or detailed");
    }

    /** Recorded JSON provenance stays unknown when absent and rejects an empty claimed value. */
    @JsonCreator
    public static AnalysisReasonLength fromRecordedValue(String value) {
        if (value == null) return null;
        if (value.isBlank()) {
            throw new IllegalArgumentException("Analysis reason length must be short, standard or detailed");
        }
        return fromValue(value);
    }
}
