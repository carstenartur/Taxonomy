package com.taxonomy.dto;

import java.util.Set;

/**
 * Describes an available import mapping profile.
 *
 * <p>Returned by the {@code GET /api/import/profiles} endpoint so the UI
 * can display a dropdown with all available profiles and their supported types.
 */
public record ProfileInfo(
    String profileId,
    String displayName,
    Set<String> supportedElementTypes,
    Set<String> supportedRelationTypes,
    String acceptedFileFormat
) {}
