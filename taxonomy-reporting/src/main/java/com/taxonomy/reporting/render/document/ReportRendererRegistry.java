package com.taxonomy.reporting.render.document;

import com.taxonomy.extension.api.report.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.BuiltinCatalog;
import com.taxonomy.shared.extension.ExtensionKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** Report-family facade over the catalog; decorators execute inside the version-bound lease. */
@Service
public class ReportRendererRegistry {
    private final ExtensionCatalog catalog;
    private final List<ReportRendererDecorator> decorators;
    public ReportRendererRegistry(List<ReportRendererExtension> extensions) { this(extensions, List.of()); }
    public ReportRendererRegistry(List<ReportRendererExtension> extensions, List<ReportRendererDecorator> decorators) {
        this(BuiltinCatalog.create(extensions == null ? List.of() : extensions), decorators);
    }
    @Autowired public ReportRendererRegistry(ExtensionCatalog catalog, List<ReportRendererDecorator> decorators) {
        this.catalog = Objects.requireNonNull(catalog);
        this.decorators = decorators == null ? List.of() : List.copyOf(decorators);
    }
    public ReportRendererExtension getRequired(String format) { return getRequired(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID, format); }
    public ReportRendererExtension getRequired(String family, String format) {
        return findByFormatId(family, format).orElseThrow(() -> new IllegalArgumentException("Unknown report renderer: " + family + "/" + format));
    }
    public Optional<ReportRendererExtension> findByFormatId(String format) { return findByFormatId(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID, format); }
    public Optional<ReportRendererExtension> findByFormatId(String family, String format) {
        if (family == null || family.isBlank() || format == null || format.isBlank()) return Optional.empty();
        var key = ExtensionKey.report(family, format);
        try (var lease = catalog.acquire(key, ReportRendererExtension.class)) {
            var metadata = lease.extension().descriptor();
            var model = lease.extension().reportModelType();
            var reportFamily = lease.extension().reportTypeId();
            return Optional.of(new ReportRendererExtension() {
                public String reportTypeId() { return reportFamily; }
                public Class<?> reportModelType() { return model; }
                public ReportFormatDescriptor descriptor() { return metadata; }
                public ReportRenderResult render(ReportRenderContext context) {
                    try (var admitted = catalog.acquire(key, ReportRendererExtension.class)) {
                        ReportRendererExtension effective = admitted.extension();
                        for (ReportRendererDecorator decorator : decorators) {
                            if (decorator.supports(effective)) effective = Objects.requireNonNull(decorator.decorate(effective), "report renderer decorator returned null");
                        }
                        return effective.render(context);
                    }
                }
            });
        } catch (ExtensionUnavailableException unavailable) { return Optional.empty(); }
    }
    public List<ReportFormatDescriptor> listDescriptors() { return listDescriptors(ReportRendererExtension.DEFAULT_REPORT_TYPE_ID); }
    public List<ReportFormatDescriptor> listDescriptors(String family) {
        if (family == null || family.isBlank()) return List.of();
        var leases = catalog.acquireAll(ExtensionKind.REPORT_RENDERER, ReportRendererExtension.class);
        try {
            return leases.stream().map(ExtensionLease::extension)
                    .filter(renderer -> renderer.reportTypeId().trim().equalsIgnoreCase(family.trim()))
                    .map(ReportRendererExtension::descriptor).sorted(Comparator.comparing(ReportFormatDescriptor::id)).toList();
        } finally { leases.forEach(ExtensionLease::close); }
    }
    public List<String> listReportTypeIds() {
        var leases = catalog.acquireAll(ExtensionKind.REPORT_RENDERER, ReportRendererExtension.class);
        try { return leases.stream().map(lease -> lease.extension().reportTypeId().trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList(); }
        finally { leases.forEach(ExtensionLease::close); }
    }
}
