package com.taxonomy.catalog.service.importer;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for import profile extensions.
 *
 * <p>All Spring beans implementing {@link ImportProfileExtension} are collected
 * here automatically.  Use {@link #getRequired(String)} or {@link #findById(String)}
 * to look up a profile, and {@link #listDescriptors()} to enumerate available profiles.
 */
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
                            registration.normalizedProfileId(),
                            registration.extension());
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate import profile ID: " + registration.normalizedProfileId());
                    }
                });
        this.byProfileId = Map.copyOf(map);
    }

    /**
     * Returns the extension for the given profile ID.
     *
     * @throws IllegalArgumentException if no extension is registered for the ID
     */
    public ImportProfileExtension getRequired(String profileId) {
        return findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown import profile: " + profileId));
    }

    /**
     * Returns the extension for the given profile ID, or empty if not found.
     */
    public Optional<ImportProfileExtension> findById(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProfileId.get(normalize(profileId)));
    }

    /**
     * Lists the descriptors of all registered import profiles.
     */
    public List<ImportProfileDescriptor> listDescriptors() {
        return byProfileId.values().stream()
                .map(ImportProfileExtension::descriptor)
                .toList();
    }

    private String normalize(String profileId) {
        return profileId.trim().toLowerCase(Locale.ROOT);
    }

    private Registration validatedRegistration(ImportProfileExtension extension) {
        ImportProfileDescriptor descriptor = extension.descriptor();
        if (descriptor == null) {
            throw new IllegalStateException(
                    "Import profile extension %s returned a null descriptor"
                            .formatted(extension.getClass().getName()));
        }
        String profileId = descriptor.profileId();
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalStateException(
                    "Import profile extension %s must declare a non-blank profile ID"
                            .formatted(extension.getClass().getName()));
        }
        return new Registration(normalize(profileId), extension);
    }

    private record Registration(String normalizedProfileId, ImportProfileExtension extension) {
    }
}
