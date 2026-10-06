package com.taxonomy.search;

/**
 * Shared failure contract between search providers and their callers.
 * A failed search is unavailable, not a completed query with no matches.
 */
public final class SearchUnavailableException extends IllegalStateException {
    public SearchUnavailableException() {
        // Backend causes can contain query text, credentials or private paths.
        // Operation-specific diagnostic codes are logged at the failure boundary.
        super("Search is temporarily unavailable.");
    }
}
