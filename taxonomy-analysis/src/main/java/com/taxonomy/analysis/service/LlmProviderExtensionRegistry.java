package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spring registry for LLM provider metadata adapters. */
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
                            registration.normalizedProviderId(), registration.extension());
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate LLM provider ID: " + registration.normalizedProviderId());
                    }
                });
        this.byProviderId = Map.copyOf(map);
    }

    public LlmProviderExtension getRequired(LlmProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        return findById(provider.name())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No LlmProviderExtension registered for provider: " + provider));
    }

    public Optional<LlmProviderExtension> findById(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProviderId.get(normalize(providerId)));
    }

    public List<LlmProviderDescriptor> listDescriptors() {
        return byProviderId.values().stream()
                .map(LlmProviderExtension::descriptor)
                .sorted(Comparator.comparing(LlmProviderDescriptor::providerId))
                .toList();
    }

    private Registration validatedRegistration(LlmProviderExtension extension) {
        if (extension == null || extension.descriptor() == null) {
            throw new IllegalStateException("LLM provider extension must declare a descriptor");
        }
        String providerId = extension.descriptor().providerId();
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException(
                    "LLM provider extension %s must declare a non-blank provider ID"
                            .formatted(extension.getClass().getName()));
        }
        String normalized = normalize(providerId);
        try {
            LlmProvider.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "LLM provider extension %s declares unknown runtime provider ID '%s'"
                            .formatted(extension.getClass().getName(), providerId), e);
        }
        return new Registration(normalized, extension);
    }

    private String normalize(String providerId) {
        return providerId.trim().toUpperCase(Locale.ROOT);
    }

    private record Registration(String normalizedProviderId, LlmProviderExtension extension) {
    }
}
