package com.taxonomy.catalog.service.importer;

import com.taxonomy.extension.api.importer.ImportProfileDescriptor;
import com.taxonomy.extension.api.importer.ImportProfileExtension;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spring registry for framework import profile adapters. */
@Service
public class ImportProfileRegistry {

    private final Map<String, ImportProfileExtension> byProfileId;

    public ImportProfileRegistry(List<ImportProfileExtension> extensions) {
        Map<String, ImportProfileExtension> map = new LinkedHashMap<>();
        extensions.stream()
                .map(this::validatedRegistration)
                .sorted(Comparator.comparing(Registration::normalizedProfileId))
                .forEach(registration -> {
                    ImportProfileExtension previous = map.putIfAbsent(
                            registration.normalizedProfileId(), registration.extension());
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate import profile ID: " + registration.normalizedProfileId());
                    }
                });
        this.byProfileId = Map.copyOf(map);
    }

    public ImportProfileExtension getRequired(String profileId) {
        return findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown import profile: " + profileId));
    }

    public Optional<ImportProfileExtension> findById(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProfileId.get(normalize(profileId)));
    }

    public List<ImportProfileDescriptor> listDescriptors() {
        return byProfileId.values().stream()
                .map(ImportProfileExtension::descriptor)
                .toList();
    }

    private Registration validatedRegistration(ImportProfileExtension extension) {
        if (extension == null || extension.descriptor() == null) {
            throw new IllegalStateException("Import profile extension must declare a descriptor");
        }
        String profileId = extension.descriptor().profileId();
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalStateException(
                    "Import profile extension %s must declare a non-blank profile ID"
                            .formatted(extension.getClass().getName()));
        }
        return new Registration(normalize(profileId), extension);
    }

    private String normalize(String profileId) {
        return profileId.trim().toLowerCase(Locale.ROOT);
    }

    private record Registration(String normalizedProfileId, ImportProfileExtension extension) {
    }
}
