package com.taxonomy.analysis.demo;

import com.taxonomy.extension.api.llm.ProviderId;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable identity of a product-demo replay binding.
 *
 * <p>The scenario and iteration are part of the persisted provider string, so
 * asynchronous workers, recovery and retries keep the exact binding without any
 * thread-local or global switch. Such a provider is never a live model.</p>
 */
public final class DemoProviderIds {
    public static final String PREFIX = "DEMO.";
    private static final Pattern PATTERN = Pattern.compile("DEMO\\.([A-Z0-9][A-Z0-9-]{0,63})\\.(R[0-9]{1,2})");

    private DemoProviderIds() { }

    /** Exact scenario/iteration binding. */
    public record Binding(String scenarioId, String iteration) {
        public Binding {
            if (scenarioId == null || !scenarioId.matches("[a-z0-9][a-z0-9-]{0,63}"))
                throw new IllegalArgumentException("Invalid demo scenario ID");
            if (iteration == null || !iteration.matches("R[0-9]{1,2}"))
                throw new IllegalArgumentException("Invalid demo iteration");
        }

        public ProviderId providerId() {
            return new ProviderId(PREFIX + scenarioId.toUpperCase(Locale.ROOT) + "." + iteration);
        }

        public int iterationNumber() { return Integer.parseInt(iteration.substring(1)); }
    }

    public static boolean isDemo(String provider) {
        return provider != null && provider.strip().toUpperCase(Locale.ROOT).startsWith(PREFIX);
    }

    public static boolean isDemo(ProviderId provider) {
        return provider != null && isDemo(provider.value());
    }

    public static Optional<Binding> parse(String provider) {
        if (!isDemo(provider)) return Optional.empty();
        var matcher = PATTERN.matcher(provider.strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new Binding(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2)));
    }

    public static Optional<Binding> parse(ProviderId provider) {
        return provider == null ? Optional.empty() : parse(provider.value());
    }

    public static ProviderId providerId(String scenarioId, int iteration) {
        return new Binding(scenarioId, "R" + iteration).providerId();
    }
}
