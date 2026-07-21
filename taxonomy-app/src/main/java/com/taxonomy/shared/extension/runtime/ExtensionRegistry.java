package com.taxonomy.shared.extension.runtime;

import com.taxonomy.shared.extension.ExtensionDescriptor;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spring runtime registry for all internal taxonomy extension beans. */
@Service
public class ExtensionRegistry {

    private final Map<ExtensionKind, Map<String, TaxonomyExtension>> byKind;

    public ExtensionRegistry(List<TaxonomyExtension> extensions) {
        EnumMap<ExtensionKind, Map<String, TaxonomyExtension>> registrations =
                new EnumMap<>(ExtensionKind.class);
        for (ExtensionKind kind : ExtensionKind.values()) {
            registrations.put(kind, new LinkedHashMap<>());
        }
        extensions.stream()
                .map(this::validatedRegistration)
                .sorted(Comparator.comparing(Registration::kind)
                        .thenComparing(Registration::normalizedId))
                .forEach(registration -> {
                    Map<String, TaxonomyExtension> extensionsById =
                            registrations.get(registration.kind());
                    TaxonomyExtension previous = extensionsById.putIfAbsent(
                            registration.normalizedId(), registration.extension());
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate extension ID for kind %s: normalized ID '%s' conflicts between %s (id='%s') and %s (id='%s')"
                                        .formatted(
                                                registration.kind(),
                                                registration.normalizedId(),
                                                previous.getClass().getName(),
                                                previous.id(),
                                                registration.extension().getClass().getName(),
                                                registration.extension().id()));
                    }
                });

        EnumMap<ExtensionKind, Map<String, TaxonomyExtension>> immutable =
                new EnumMap<>(ExtensionKind.class);
        registrations.forEach((kind, values) -> immutable.put(kind, Map.copyOf(values)));
        this.byKind = Map.copyOf(immutable);
    }

    public List<ExtensionDescriptor> listAll() {
        return byKind.keySet().stream()
                .sorted()
                .flatMap(kind -> listByKind(kind).stream())
                .toList();
    }

    public List<ExtensionDescriptor> listByKind(ExtensionKind kind) {
        if (kind == null) {
            return List.of();
        }
        return byKind.getOrDefault(kind, Map.of()).values().stream()
                .map(TaxonomyExtension::extensionDescriptor)
                .sorted(Comparator.comparing(
                                (ExtensionDescriptor descriptor) -> normalize(descriptor.id()))
                        .thenComparing(ExtensionDescriptor::id))
                .toList();
    }

    public Optional<ExtensionDescriptor> findDescriptor(ExtensionKind kind, String id) {
        if (kind == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKind.getOrDefault(kind, Map.of()).get(normalize(id)))
                .map(TaxonomyExtension::extensionDescriptor);
    }

    public ExtensionDescriptor getRequiredDescriptor(ExtensionKind kind, String id) {
        return findDescriptor(kind, id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown extension %s/%s".formatted(kind, id)));
    }

    private Registration validatedRegistration(TaxonomyExtension extension) {
        if (extension == null) {
            throw new IllegalStateException("A null TaxonomyExtension was passed to the registry");
        }
        if (extension.kind() == null) {
            throw new IllegalStateException(
                    "Extension %s returned a null kind".formatted(extension.getClass().getName()));
        }
        if (extension.id() == null || extension.id().isBlank()) {
            throw new IllegalStateException(
                    "Extension %s must declare a non-blank ID"
                            .formatted(extension.getClass().getName()));
        }
        if (extension.displayName() == null || extension.displayName().isBlank()) {
            throw new IllegalStateException(
                    "Extension %s must declare a non-blank display name"
                            .formatted(extension.getClass().getName()));
        }
        if (extension.description() == null) {
            throw new IllegalStateException(
                    "Extension %s must declare a non-null description"
                            .formatted(extension.getClass().getName()));
        }
        return new Registration(extension.kind(), normalize(extension.id()), extension);
    }

    private String normalize(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private record Registration(
            ExtensionKind kind, String normalizedId, TaxonomyExtension extension) {
    }
}
