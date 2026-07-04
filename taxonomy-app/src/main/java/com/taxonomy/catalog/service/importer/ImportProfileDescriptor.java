package com.taxonomy.catalog.service.importer;

import java.io.Serializable;
import java.util.Set;

/**
 * Serializable descriptor for an import profile extension.
 *
 * @param profileId            unique profile identifier (e.g. {@code "uaf"}, {@code "apqc"})
 * @param displayName          human-readable profile name shown in the UI
 * @param supportedElementTypes set of external element types this profile can map
 * @param supportedRelationTypes set of external relation types this profile can map
 * @param acceptedFileFormat   file format accepted by the parser (e.g. {@code "xml"}, {@code "csv"})
 */
public record ImportProfileDescriptor(
        String profileId,
        String displayName,
        Set<String> supportedElementTypes,
        Set<String> supportedRelationTypes,
        String acceptedFileFormat) implements Serializable {
}
