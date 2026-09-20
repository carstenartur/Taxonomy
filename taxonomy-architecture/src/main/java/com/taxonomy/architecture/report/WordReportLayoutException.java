package com.taxonomy.architecture.report;

/** The selected template cannot contain the report evidence at its required reading scale. */
public final class WordReportLayoutException extends IllegalArgumentException {
    public WordReportLayoutException(String message) {
        super(message);
    }
}
