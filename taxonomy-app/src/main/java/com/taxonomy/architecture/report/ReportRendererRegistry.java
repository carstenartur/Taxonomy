package com.taxonomy.architecture.report;

import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring registry for report-renderer extensions grouped by report family and format.
 */
@Service
public class ReportRendererRegistry {

    private final Map<String, Map<String, ReportRendererExtension>> byReportType;

    public ReportRendererRegistry(List<ReportRendererExtension> extensions) {
        List<ReportRendererExtension> safeExtensions = extensions == null
                ? List.of() : new ArrayList<>(extensions);
        for (ReportRendererExtension extension : safeExtensions) {
            Objects.requireNonNull(extension, "report renderer extension must not be null");
            Objects.requireNonNull(extension.descriptor(),
                    "report renderer descriptor must not be null");
            Objects.requireNonNull(extension.reportModelType(),
                    "report renderer model type must not be null");
            normalizeRequired(extension.reportTypeId(), "report type ID");
            normalizeRequired(extension.descriptor().id(), "report format ID");
        }

        Map<String, Map<String, ReportRendererExtension>> reportTypes = new LinkedHashMap<>();
        safeExtensions.stream()
                .sorted(Comparator
                        .comparing((ReportRendererExtension extension) ->
                                normalize(extension.reportTypeId()))
                        .thenComparing(extension -> normalize(extension.descriptor().id())))
                .forEach(extension -> {
                    String reportType = normalizeRequired(
                            extension.reportTypeId(), "report type ID");
                    String formatId = normalizeRequired(
                            extension.descriptor().id(), "report format ID");
                    Map<String, ReportRendererExtension> formats =
                            reportTypes.computeIfAbsent(reportType, ignored -> new LinkedHashMap<>());
                    ReportRendererExtension previous = formats.putIfAbsent(formatId, extension);
                    if (previous != null) {
                        if (ReportRendererExtension.DEFAULT_REPORT_TYPE_ID.equals(reportType)) {
                            throw new IllegalStateException(
                                    "Duplicate report renderer format ID: " + formatId);
                        }
                        throw new IllegalStateException(
                                "Duplicate report renderer for report type " + reportType
                                        + " and format " + formatId);
                    }
                });

        Map<String, Map<String, ReportRendererExtension>> immutable = new LinkedHashMap<>();
        reportTypes.forEach((reportType, formats) ->
                immutable.put(reportType, Map.copyOf(formats)));
        this.byReportType = Map.copyOf(immutable);
    }

    /** Backward-compatible lookup for the architecture-report family. */
    public ReportRendererExtension getRequired(String formatId) {
        return getRequired(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID, formatId);
    }

    public ReportRendererExtension getRequired(String reportTypeId, String formatId) {
        return findByFormatId(reportTypeId, formatId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown report renderer: " + reportTypeId + "/" + formatId));
    }

    /** Backward-compatible lookup for the architecture-report family. */
    public Optional<ReportRendererExtension> findByFormatId(String formatId) {
        return findByFormatId(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID, formatId);
    }

    public Optional<ReportRendererExtension> findByFormatId(
            String reportTypeId,
            String formatId) {
        if (reportTypeId == null || reportTypeId.isBlank()
                || formatId == null || formatId.isBlank()) {
            return Optional.empty();
        }
        Map<String, ReportRendererExtension> formats =
                byReportType.get(normalize(reportTypeId));
        if (formats == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(formats.get(normalize(formatId)));
    }

    /** Backward-compatible descriptor list for architecture reports. */
    public List<ReportFormatDescriptor> listDescriptors() {
        return listDescriptors(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID);
    }

    public List<ReportFormatDescriptor> listDescriptors(String reportTypeId) {
        if (reportTypeId == null || reportTypeId.isBlank()) {
            return List.of();
        }
        Map<String, ReportRendererExtension> formats =
                byReportType.get(normalize(reportTypeId));
        if (formats == null) {
            return List.of();
        }
        return formats.values().stream()
                .map(ReportRendererExtension::descriptor)
                .sorted(Comparator.comparing(ReportFormatDescriptor::id))
                .toList();
    }

    public List<String> listReportTypeIds() {
        return byReportType.keySet().stream().sorted().toList();
    }

    private String normalizeRequired(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = normalize(value);
        if (normalized.contains(":")) {
            throw new IllegalArgumentException(label + " must not contain ':'");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
