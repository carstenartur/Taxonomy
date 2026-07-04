package com.taxonomy.architecture.report;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for report renderer extensions.
 */
@Service
public class ReportRendererRegistry {

    private final Map<String, ReportRendererExtension> byFormatId;

    public ReportRendererRegistry(List<ReportRendererExtension> extensions) {
        Map<String, ReportRendererExtension> map = new LinkedHashMap<>();
        extensions.stream()
                .sorted(Comparator.comparing(ext -> ext.descriptor().id()))
                .forEach(extension -> {
                    String key = normalize(extension.descriptor().id());
                    ReportRendererExtension previous = map.putIfAbsent(key, extension);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate report renderer format ID: " + key);
                    }
                });
        this.byFormatId = Map.copyOf(map);
    }

    public ReportRendererExtension getRequired(String formatId) {
        return findByFormatId(formatId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown report format: " + formatId));
    }

    public Optional<ReportRendererExtension> findByFormatId(String formatId) {
        if (formatId == null || formatId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byFormatId.get(normalize(formatId)));
    }

    public List<ReportFormatDescriptor> listDescriptors() {
        return byFormatId.values().stream()
                .map(ReportRendererExtension::descriptor)
                .toList();
    }

    private String normalize(String formatId) {
        return formatId.toLowerCase(Locale.ROOT);
    }
}
