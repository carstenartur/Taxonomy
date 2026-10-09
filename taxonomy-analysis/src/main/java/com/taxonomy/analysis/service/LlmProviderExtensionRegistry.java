package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.BuiltinCatalog;
import com.taxonomy.shared.extension.ExtensionKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only provider facade. Provider plugins are STARTUP-only, never hot-swapped. */
@Service
public class LlmProviderExtensionRegistry {
    private final ExtensionCatalog catalog;
    public LlmProviderExtensionRegistry(List<LlmProviderExtension> extensions) {
        this(BuiltinCatalog.create(validate(extensions)));
    }
    @Autowired public LlmProviderExtensionRegistry(ExtensionCatalog catalog) { this.catalog = catalog; }
    public LlmProviderExtension getRequired(LlmProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        return findById(provider.name()).orElseThrow(() -> new IllegalArgumentException("No LlmProviderExtension registered for provider: " + provider));
    }
    public Optional<LlmProviderExtension> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.LLM_PROVIDER, id), LlmProviderExtension.class)) {
            // Startup contributions live until application shutdown. Execution still uses the
            // host-managed gateway, not a metadata accessor on this facade.
            return Optional.of(lease.extension());
        } catch (ExtensionUnavailableException unavailable) { return Optional.empty(); }
    }
    public List<LlmProviderDescriptor> listDescriptors() {
        var leases = catalog.acquireAll(ExtensionKind.LLM_PROVIDER, LlmProviderExtension.class);
        try { return leases.stream().map(lease -> lease.extension().descriptor()).sorted(Comparator.comparing(LlmProviderDescriptor::providerId)).toList(); }
        finally { leases.forEach(ExtensionLease::close); }
    }
    private static List<LlmProviderExtension> validate(List<LlmProviderExtension> extensions) {
        for (var extension : extensions) {
            if (extension == null || extension.descriptor() == null) throw new IllegalStateException("LLM provider extension must declare a descriptor");
            try { new ProviderId(extension.descriptor().providerId()); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid LLM provider ID", invalid); }
        }
        return extensions;
    }
}
