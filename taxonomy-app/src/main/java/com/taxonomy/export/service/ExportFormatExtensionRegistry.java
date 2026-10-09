package com.taxonomy.export.service;

import com.taxonomy.export.spi.*;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.BuiltinCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

/** Export facade; each physical export holds its admitted plugin version until completion. */
@Service
public class ExportFormatExtensionRegistry {
    private final ExtensionCatalog catalog;
    public ExportFormatExtensionRegistry(List<ExportFormatExtension> extensions) { this(BuiltinCatalog.create(extensions)); }
    @Autowired public ExportFormatExtensionRegistry(ExtensionCatalog catalog) { this.catalog = catalog; }
    public ExportFormatExtension getRequired(String id) {
        return findByFormatId(id).orElseThrow(() -> new IllegalArgumentException("Unknown export format: " + id));
    }
    public Optional<ExportFormatExtension> findByFormatId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        var key = new ExtensionKey(ExtensionKind.EXPORT_FORMAT, id);
        try (var lease = catalog.acquire(key, ExportFormatExtension.class)) {
            ExportFormatDescriptor metadata = lease.extension().descriptor();
            return Optional.of(new ExportFormatExtension() {
                public ExportFormatDescriptor descriptor() { return metadata; }
                public ExportResult export(ExportContext context) {
                    try (var admitted = catalog.acquire(key, ExportFormatExtension.class)) {
                        return admitted.extension().export(context);
                    }
                }
            });
        } catch (ExtensionUnavailableException unavailable) { return Optional.empty(); }
    }
    public List<ExportFormatDescriptor> listDescriptors() {
        var leases = catalog.acquireAll(ExtensionKind.EXPORT_FORMAT, ExportFormatExtension.class);
        try { return leases.stream().map(lease -> lease.extension().descriptor()).toList(); }
        finally { leases.forEach(ExtensionLease::close); }
    }
}
