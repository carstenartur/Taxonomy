package com.taxonomy.catalog.service;

/**
 * Stable failure type for ArchiMate parsing or materialization errors.
 *
 * <p>Throwing a runtime exception from the transactional importer guarantees
 * that partially created relations are rolled back. Controllers can map this
 * exception to a deterministic non-2xx response without exposing stack traces
 * or provider-specific parser messages.</p>
 */
public class ArchiMateImportException extends RuntimeException {

    public ArchiMateImportException(String message) {
        super(message);
    }

    public ArchiMateImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
