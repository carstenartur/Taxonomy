package com.taxonomy.export.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for export format extensions.
 *
 * <p>All Spring beans implementing {@link ExportFormatExtension} are collected here
 * automatically.  Use {@link #getRequired(String)} or {@link #findByFormatId(String)}
 * to look up a format, and {@link #listDescriptors()} to enumerate available formats.
 */
@Service
public class ExportFormatExtensionRegistry {

    private final Map<String, ExportFormatExtension> byFormatId;

    public ExportFormatExtensionRegistry(List<ExportFormatExtension> extensions) {
        Map<String, ExportFormatExtension> map = new LinkedHashMap<>();
        extensions.stream()
                .sorted(Comparator.comparing(ext -> ext.descriptor().id()))
                .forEach(extension -> {
                    String key = normalize(extension.descriptor().id());
                    ExportFormatExtension previous = map.putIfAbsent(key, extension);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate export format ID: " + key);
                    }
                });
        this.byFormatId = Map.copyOf(map);
    }

    /**
     * Returns the extension for the given format ID.
     *
     * @throws IllegalArgumentException if no extension is registered for the given ID
     */
    public ExportFormatExtension getRequired(String formatId) {
        return findByFormatId(formatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown export format: " + formatId));
    }

    /**
     * Returns the extension for the given format ID, or empty if not found.
     */
    public Optional<ExportFormatExtension> findByFormatId(String formatId) {
        if (formatId == null || formatId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFormatId.get(normalize(formatId)));
    }

    /**
     * Lists the descriptors of all registered export formats, sorted by format ID.
     */
    public List<ExportFormatDescriptor> listDescriptors() {
        return byFormatId.values().stream()
                .map(ExportFormatExtension::descriptor)
                .toList();
    }

    private String normalize(String formatId) {
        return formatId.trim().toLowerCase(Locale.ROOT);
    }
}
