package com.taxonomy.extension.api.llm;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/** Spring-free extension contract for exposing one LLM provider descriptor. */
public interface LlmProviderExtension extends TaxonomyExtension {

    @Override
    default String id() {
        return descriptor().providerId();
    }

    @Override
    default String displayName() {
        return descriptor().providerName();
    }

    @Override
    default String description() {
        return "Provides LLM metadata for %s".formatted(descriptor().providerName());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.LLM_PROVIDER;
    }

    LlmProviderDescriptor descriptor();
}
