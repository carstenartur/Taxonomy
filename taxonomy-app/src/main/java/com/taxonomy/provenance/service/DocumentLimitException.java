package com.taxonomy.provenance.service;

/** Raised when a document exceeds a configured processing or expansion limit. */
public class DocumentLimitException extends RuntimeException {

    private final String code;

    public DocumentLimitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentLimitException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}