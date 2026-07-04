package com.taxonomy.analysis.service;

import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Extension SPI for LLM providers.
 *
 * <p>Each implementation describes one LLM provider — its capabilities, configuration
 * requirements, and the corresponding {@link LlmProvider} enum constant.
 * Implementations are registered as Spring {@code @Component}s and discovered
 * automatically by {@link LlmProviderExtensionRegistry}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Component
 * public class MyLlmProviderExtension implements LlmProviderExtension {
 *     @Override
 *     public LlmProviderDescriptor descriptor() { ... }
 *
 *     @Override
 *     public LlmProvider provider() { return LlmProvider.MY_PROVIDER; }
 * }
 * }</pre>
 *
 * @see LlmProviderDescriptor
 * @see LlmProviderExtensionRegistry
 */
public interface LlmProviderExtension extends TaxonomyExtension {

    /**
     * Returns the static descriptor for this provider (ID, display name, capabilities,
     * required configuration properties).
     */
    LlmProviderDescriptor descriptor();

    /**
     * Returns the {@link LlmProvider} enum constant that this extension describes.
     */
    LlmProvider provider();
}
