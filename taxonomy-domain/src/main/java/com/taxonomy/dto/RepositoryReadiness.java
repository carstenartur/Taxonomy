package com.taxonomy.dto;

/** Evidence available for the selected source's projection or index. */
public enum RepositoryReadiness {
    FRESH,
    STALE,
    MISSING,
    FAILED,
    UNVERIFIED
}
