package com.taxonomy.model;

/**
 * Describes how a requirement is related to its source material.
 */
public enum LinkType {

    /** The requirement was imported directly from the source. */
    IMPORTED_FROM,

    /** The requirement was extracted (e.g. parsed) from the source. */
    EXTRACTED_FROM,

    /** The requirement quotes a passage from the source. */
    QUOTED_FROM,

    /** The requirement is derived from (but not identical to) the source. */
    DERIVED_FROM,

    /** The requirement is confirmed or validated by the source. */
    CONFIRMED_BY,

    /** The requirement references the source for context. */
    REFERENCES
}
