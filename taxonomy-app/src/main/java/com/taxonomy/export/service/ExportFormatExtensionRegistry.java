package com.taxonomy.export.service;

import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spring registry for diagram export adapters implemented in the application layer. */
@Service
public class ExportFormatExtensionRegistry {

    private final Map<String, ExportFormatExtension> byFormatId;

    public ExportFormatExtensionRegistry(List<ExportFormatExtension> extensions) {
        Map<String, ExportFormatExtension> map = new LinkedHashMap<>();
        extensions.stream()
                .sorted(Comparator.comparing(extension -> extension.descriptor().id()))
                .forEach(extension -> {
                    String key = normalize(extension.descriptor().id());
                    ExportFormatExtension previous = map.putIfAbsent(key, extension);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate export format ID: " + key);
                    }
                });
        this.byFormatId = Map.copyOf(map);
    }

    public ExportFormatExtension getRequired(String formatId) {
        return findByFormatId(formatId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown export format: " + formatId));
    }

    public Optional<ExportFormatExtension> findByFormatId(String formatId) {
        if (formatId == null || formatId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFormatId.get(normalize(formatId)));
    }

    public List<ExportFormatDescriptor> listDescriptors() {
        return byFormatId.values().stream()
                .map(ExportFormatExtension::descriptor)
                .toList();
    }

    private String normalize(String formatId) {
        return formatId.trim().toLowerCase(Locale.ROOT);
    }
}
