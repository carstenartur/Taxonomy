package com.taxonomy.catalog.service.importer;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import com.taxonomy.extension.api.importer.ImportInput;
import com.taxonomy.extension.api.importer.ImportProfileExtension;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Generic framework import service delegating to registered profile adapters. */
@Service
public class FrameworkImportService {

    private final ImportProfileRegistry registry;

    public FrameworkImportService(ImportProfileRegistry registry) {
        this.registry = registry;
    }

    public List<ProfileInfo> getAvailableProfiles() {
        return registry.listDescriptors().stream()
                .map(descriptor -> new ProfileInfo(
                        descriptor.profileId(),
                        descriptor.displayName(),
                        descriptor.supportedElementTypes(),
                        descriptor.supportedRelationTypes(),
                        descriptor.acceptedFileFormat()))
                .toList();
    }

    public FrameworkImportResult preview(String profileId, InputStream input) {
        return registry.findById(profileId)
                .map(extension -> extension.preview(ImportInput.forPreview(input)))
                .orElseGet(() -> errorResult(profileId, "Unknown profile: " + profileId));
    }

    public FrameworkImportResult importFile(
            String profileId, InputStream input, String branch) {
        return registry.findById(profileId)
                .map(extension -> extension.importData(new ImportInput(input, branch)))
                .orElseGet(() -> errorResult(profileId, "Unknown profile: " + profileId));
    }

    private static FrameworkImportResult errorResult(String profileId, String message) {
        return new FrameworkImportResult(
                profileId, profileId, false,
                0, 0, 0, 0, 0, 0, null,
                List.of(message), List.of(), Map.of());
    }
}
