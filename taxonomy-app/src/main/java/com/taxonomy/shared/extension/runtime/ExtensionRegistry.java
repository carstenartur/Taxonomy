package com.taxonomy.shared.extension.runtime;

import com.taxonomy.shared.extension.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.BuiltinCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

/** Metadata facade over the one authoritative extension catalog. */
@Service
public class ExtensionRegistry {
    private final ExtensionCatalog catalog;
    public ExtensionRegistry(List<TaxonomyExtension> extensions) { this(BuiltinCatalog.create(extensions)); }
    @Autowired public ExtensionRegistry(ExtensionCatalog catalog) { this.catalog = catalog; }
    public List<ExtensionDescriptor> listAll() {
        return catalog.snapshot().extensions().stream().map(ExtensionRegistration::descriptor).toList();
    }
    public List<ExtensionDescriptor> listByKind(ExtensionKind kind) {
        if (kind == null) return List.of();
        return catalog.snapshot().extensions().stream().filter(e -> e.key().kind() == kind)
                .map(ExtensionRegistration::descriptor).toList();
    }
    public Optional<ExtensionDescriptor> findDescriptor(ExtensionKind kind, String id) {
        if (kind == null || id == null || id.isBlank()) return Optional.empty();
        var key = new ExtensionKey(kind, id);
        return catalog.snapshot().extensions().stream().filter(e -> e.key().equals(key))
                .map(ExtensionRegistration::descriptor).findFirst();
    }
    public ExtensionDescriptor getRequiredDescriptor(ExtensionKind kind, String id) {
        return findDescriptor(kind, id).orElseThrow(() -> new IllegalArgumentException("Unknown extension %s/%s".formatted(kind, id)));
    }
}
