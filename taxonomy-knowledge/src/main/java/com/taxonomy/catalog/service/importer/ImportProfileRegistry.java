package com.taxonomy.catalog.service.importer;

import com.taxonomy.extension.api.importer.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.BuiltinCatalog;
import com.taxonomy.shared.extension.ExtensionKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

/** Import contributions have startup lifetime and share the host catalog with other kinds. */
@Service
public class ImportProfileRegistry {
    private final ExtensionCatalog catalog;
    public ImportProfileRegistry(List<ImportProfileExtension> extensions) { this(BuiltinCatalog.create(extensions)); }
    @Autowired public ImportProfileRegistry(ExtensionCatalog catalog) { this.catalog = catalog; }
    public ImportProfileExtension getRequired(String id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown import profile: " + id));
    }
    public Optional<ImportProfileExtension> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.IMPORT_PROFILE, id), ImportProfileExtension.class)) {
            return Optional.of(lease.extension());
        } catch (ExtensionUnavailableException unavailable) { return Optional.empty(); }
    }
    public List<ImportProfileDescriptor> listDescriptors() {
        var leases = catalog.acquireAll(ExtensionKind.IMPORT_PROFILE, ImportProfileExtension.class);
        try { return leases.stream().map(lease -> lease.extension().descriptor()).toList(); }
        finally { leases.forEach(ExtensionLease::close); }
    }
}
