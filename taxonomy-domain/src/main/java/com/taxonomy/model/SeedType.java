package com.taxonomy.model;

/**
 * Classifies the provenance of a relation seed row.
 *
 * <ul>
 *   <li>{@link #TYPE_DEFAULT} — structural relations that are always expected
 *       between taxonomy element types (e.g., CP → CR REALIZES).</li>
 *   <li>{@link #FRAMEWORK_SEED} — relations derived from a specific architecture
 *       framework such as TOGAF, NAF, APQC, or FIM.</li>
 *   <li>{@link #SOURCE_DERIVED} — relations inferred from a regulatory or
 *       reference document rather than a framework standard.</li>
 * </ul>
 */
public enum SeedType {
    TYPE_DEFAULT,
    FRAMEWORK_SEED,
    SOURCE_DERIVED
}
