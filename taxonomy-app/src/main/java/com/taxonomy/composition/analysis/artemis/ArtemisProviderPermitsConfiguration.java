package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;
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
        Map<LlmProvider, String> groups = Binder.get(environment)
                .bind(prefix + "provider-groups", Bindable.mapOf(LlmProvider.class, String.class)).orElse(Map.of());
        if (groups.isEmpty()) throw new IllegalArgumentException("Cluster provider permits require explicit provider-groups");
        return new ArtemisProviderPermitSettings(environment.getProperty(prefix + "destination-prefix",
                ArtemisProviderPermitSettings.DEFAULT_PREFIX), groups,
                environment.getProperty(prefix + "maximum-wait-ms", Long.class, 120_000L));
    }

    @Bean
    ArtemisProviderConcurrencyPermits artemisProviderConcurrencyPermits(
            ArtemisAnalysisConnection connection, ArtemisProviderPermitSettings settings) {
        return new ArtemisProviderConcurrencyPermits(connection, settings);
    }
}
