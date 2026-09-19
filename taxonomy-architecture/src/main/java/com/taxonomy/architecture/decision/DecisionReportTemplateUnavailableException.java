package com.taxonomy.architecture.decision;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Signals that a required versioned report template is missing or unusable.
 *
 * <p>The report request itself may be valid, but the server cannot fulfil it until an
 * administrator restores a valid template. Mapping this condition to 503 keeps it
 * distinct from malformed report input and from an unexpected renderer defect.</p>
 */
@ResponseStatus(code = HttpStatus.SERVICE_UNAVAILABLE)
public final class DecisionReportTemplateUnavailableException
        extends IllegalStateException {

    public DecisionReportTemplateUnavailableException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
