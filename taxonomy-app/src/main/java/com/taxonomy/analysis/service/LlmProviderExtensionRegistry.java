package com.taxonomy.analysis.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for LLM provider extensions.
 *
 * <p>All Spring beans implementing {@link LlmProviderExtension} are collected here
 * automatically.  Use {@link #getRequired(LlmProvider)} or {@link #findById(String)}
 * to look up a provider, and {@link #listDescriptors()} to enumerate available providers.
 */
@Service
public class LlmProviderExtensionRegistry {

    private final Map<String, LlmProviderExtension> byProviderId;

    public LlmProviderExtensionRegistry(List<LlmProviderExtension> extensions) {
        Map<String, LlmProviderExtension> map = new LinkedHashMap<>();
        extensions.stream()
                .map(this::validatedRegistration)
                .sorted(Comparator.comparing(Registration::normalizedProviderId))
                .forEach(registration -> {
                    LlmProviderExtension previous = map.putIfAbsent(
                            registration.normalizedProviderId(),
                            registration.extension());
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate LLM provider ID: " + registration.normalizedProviderId());
                    }
                });
        this.byProviderId = Map.copyOf(map);
    }

    /**
     * Returns the extension for the given provider.
     *
     * @throws IllegalArgumentException if {@code provider} is null or no extension is registered for it
     */
    public LlmProviderExtension getRequired(LlmProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        return findById(provider.name())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No LlmProviderExtension registered for provider: " + provider));
    }

    /**
     * Returns the extension for the given provider ID string, or empty if not found.
     */
    public Optional<LlmProviderExtension> findById(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProviderId.get(normalize(providerId)));
    }

    /**
     * Lists the descriptors of all registered LLM providers, sorted by provider ID.
     */
    public List<LlmProviderDescriptor> listDescriptors() {
        return byProviderId.values().stream()
                .map(LlmProviderExtension::descriptor)
                .sorted(Comparator.comparing(LlmProviderDescriptor::providerId))
                .toList();
    }

    private String normalize(String providerId) {
        return providerId.trim().toUpperCase(Locale.ROOT);
    }

    private Registration validatedRegistration(LlmProviderExtension extension) {
        if (extension == null) {
            throw new IllegalStateException("A null LlmProviderExtension was passed to the registry");
        }
        LlmProviderDescriptor descriptor = extension.descriptor();
        if (descriptor == null) {
            throw new IllegalStateException(
                    "LLM provider extension %s returned a null descriptor"
                            .formatted(extension.getClass().getName()));
        }
        String providerId = descriptor.providerId();
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException(
                    "LLM provider extension %s must declare a non-blank provider ID"
                            .formatted(extension.getClass().getName()));
        }
        LlmProvider provider = extension.provider();
        if (provider == null) {
            throw new IllegalStateException(
                    "LLM provider extension %s returned a null provider"
                            .formatted(extension.getClass().getName()));
        }
        if (!provider.name().equals(providerId)) {
            throw new IllegalStateException(
                    "LLM provider extension %s has mismatched providerId '%s' and provider().name() '%s'"
                            .formatted(extension.getClass().getName(), providerId, provider.name()));
        }
        return new Registration(normalize(providerId), extension);
    }

    private record Registration(String normalizedProviderId, LlmProviderExtension extension) {
    }
}
