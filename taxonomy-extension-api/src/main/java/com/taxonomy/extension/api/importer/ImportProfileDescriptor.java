package com.taxonomy.extension.api.importer;

import java.io.Serializable;
import java.util.Set;

/** Serializable metadata for one framework import profile. */
public record ImportProfileDescriptor(
        String profileId,
        String displayName,
        Set<String> supportedElementTypes,
        Set<String> supportedRelationTypes,
        String acceptedFileFormat) implements Serializable {
}
