package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Map;

/** Independent opt-in; the existing gateway registry injects its neutral permit port. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taxonomy.analysis.provider-permits.enabled", havingValue = "true")
public class ArtemisProviderPermitsConfiguration {
    @Bean
    ArtemisProviderPermitSettings artemisProviderPermitSettings(Environment environment) {
        if (!ArtemisAnalysisSettings.artemisMode(environment.getProperty("taxonomy.analysis.transport.mode", "local"))) {
            throw new IllegalStateException("Cluster provider permits require artemis analysis transport");
        }
        String prefix = "taxonomy.analysis.provider-permits.";
        Map<String, String> configured = Binder.get(environment)
                .bind(prefix + "provider-groups", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        Map<ProviderId, String> groups = new java.util.LinkedHashMap<>();
        configured.forEach((key, group) -> {
            // Retain Spring's former enum binding aliases, without rewriting external IDs.
            ProviderId literal = new ProviderId(key);
            ProviderId id = LlmProvider.builtin(new ProviderId(key.replace('-', '_')))
                    .map(LlmProvider::id).orElse(literal);
            if (groups.putIfAbsent(id, group) != null) {
                throw new IllegalArgumentException("Duplicate provider quota group: " + id);
            }
        });
        if (groups.isEmpty()) throw new IllegalArgumentException("Cluster provider permits require explicit provider-groups");
        return new ArtemisProviderPermitSettings(environment.getProperty(prefix + "destination-prefix",
                ArtemisProviderPermitSettings.DEFAULT_PREFIX), groups,
                environment.getProperty(prefix + "maximum-wait-ms", Long.class, 120_000L));
    }

    @Bean
    ProviderConcurrencyPermits artemisProviderConcurrencyPermits(
            ArtemisAnalysisConnection connection, ArtemisProviderPermitSettings settings,
            ObjectProvider<ArtemisAnalysisMetrics> instrumentation) {
        var permits = new ArtemisProviderConcurrencyPermits(connection, settings);
        var metrics = instrumentation.getIfAvailable();
        return metrics == null ? permits : metrics.permits(permits, settings);
    }
}
