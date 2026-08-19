package com.taxonomy.analysis.session;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a stale browser tab attempts to overwrite a newer draft. */
@ResponseStatus(HttpStatus.CONFLICT)
public class AnalysisDraftConflictException extends RuntimeException {

    public AnalysisDraftConflictException(String message) {
        super(message);
    }

    public AnalysisDraftConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
