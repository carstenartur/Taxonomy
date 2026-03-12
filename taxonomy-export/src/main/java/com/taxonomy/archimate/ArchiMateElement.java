package com.taxonomy.archimate;

/**
 * Represents an ArchiMate element with a type mapped from the taxonomy type.
 */
public record ArchiMateElement(
        String id,
        String label,
        String archiMateType,
        String documentation) {}
