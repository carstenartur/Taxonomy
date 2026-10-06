package com.taxonomy.search;

/** A failed search is unavailable, not a completed query with no matches. */
public final class SearchUnavailableException extends IllegalStateException {
    public SearchUnavailableException() {
        // Backend causes can contain query text, credentials or private paths.
        // Operation-specific diagnostic codes are logged at the failure boundary.
        super("Search is temporarily unavailable.");
    }
}
