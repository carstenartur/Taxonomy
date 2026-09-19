package com.taxonomy.analysis.session;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised for malformed or excessively large draft payloads. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class AnalysisDraftValidationException extends RuntimeException {

    public AnalysisDraftValidationException(String message) {
        super(message);
    }

    public AnalysisDraftValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
