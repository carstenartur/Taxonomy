package com.taxonomy.catalog.service.importer;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Generic framework import service that delegates to {@link ImportProfileRegistry}.
 *
 * <p>Registered profiles (backed by {@link ImportProfileExtension} adapters):
 * <ul>
 *   <li>{@code uaf} — UAF/DoDAF XMI XML ({@link UafImportProfileExtension})</li>
 *   <li>{@code apqc} — APQC PCF CSV ({@link ApqcCsvImportProfileExtension})</li>
 *   <li>{@code apqc-excel} — APQC PCF Excel ({@link ApqcExcelImportProfileExtension})</li>
 *   <li>{@code c4} — C4/Structurizr DSL ({@link C4ImportProfileExtension})</li>
 * </ul>
 *
 * <p>The public API of this class is unchanged so that {@link com.taxonomy.catalog.controller.ImportApiController}
 * requires no modification.
 */
@Service
public class FrameworkImportService {

    private final ImportProfileRegistry registry;

    public FrameworkImportService(ImportProfileRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns info about all available import profiles.
     */
    public List<ProfileInfo> getAvailableProfiles() {
        return registry.listDescriptors().stream()
                .map(d -> new ProfileInfo(
                        d.profileId(),
                        d.displayName(),
                        d.supportedElementTypes(),
                        d.supportedRelationTypes(),
                        d.acceptedFileFormat()))
                .toList();
    }

    /**
     * Preview an import (dry run): parse and map but do not materialize.
     */
    public FrameworkImportResult preview(String profileId, InputStream input) {
        return registry.findById(profileId)
                .map(ext -> ext.preview(ImportInput.forPreview(input)))
                .orElseGet(() -> errorResult(profileId, "Unknown profile: " + profileId));
    }

    /**
     * Full import: parse, map, serialize to DSL, and materialize into the database.
     */
    public FrameworkImportResult importFile(String profileId, InputStream input, String branch) {
        return registry.findById(profileId)
                .map(ext -> ext.importData(new ImportInput(input, branch)))
                .orElseGet(() -> errorResult(profileId, "Unknown profile: " + profileId));
    }

    private static FrameworkImportResult errorResult(String profileId, String message) {
        return new FrameworkImportResult(
                profileId, profileId, false,
                0, 0, 0, 0, 0, 0, null,
                List.of(message), List.of(), Map.of());
    }
}
