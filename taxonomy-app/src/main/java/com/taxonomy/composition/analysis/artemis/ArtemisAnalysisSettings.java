package com.taxonomy.composition.analysis.artemis;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Validated, credential-free view of the Artemis transport configuration.
 * Fails closed: an unknown transport mode, a missing broker URL or a plaintext
 * TCP URL while TLS is required stop the application instead of silently
 * falling back.
 */
public record ArtemisAnalysisSettings(String brokerUrl, boolean requireTls, long retryIntervalMs,
                                      long callTimeoutMs) {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_ARTEMIS = "artemis";

    public ArtemisAnalysisSettings {
        if (brokerUrl == null || brokerUrl.isBlank()) {
            throw new IllegalStateException("taxonomy.analysis.artemis.broker-url is required in artemis transport mode");
        }
        brokerUrl = brokerUrl.trim();
        String scheme = scheme(brokerUrl);
        if (!scheme.equals("tcp") && !scheme.equals("vm")) {
            throw new IllegalStateException("taxonomy.analysis.artemis.broker-url must use tcp:// or vm://");
        }
        if (requireTls && scheme.equals("tcp") && !tlsEnabled(brokerUrl)) {
            throw new IllegalStateException("taxonomy.analysis.artemis.broker-url must set sslEnabled=true "
                    + "(or set taxonomy.analysis.artemis.require-tls=false for an isolated network)");
        }
        if (retryIntervalMs < 100 || retryIntervalMs > 60_000) {
            throw new IllegalStateException("taxonomy.analysis.artemis.retry-interval-ms must be within 100..60000");
        }
        if (callTimeoutMs < 1_000 || callTimeoutMs > 300_000) {
            throw new IllegalStateException("taxonomy.analysis.artemis.call-timeout-ms must be within 1000..300000");
        }
    }

    /** Validate the transport mode; returns {@code true} for Artemis. */
    public static boolean artemisMode(String mode) {
        String normalized = Objects.requireNonNullElse(mode, MODE_LOCAL).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", MODE_LOCAL -> false;
            case MODE_ARTEMIS -> true;
            default -> throw new IllegalStateException(
                    "taxonomy.analysis.transport.mode must be 'local' or 'artemis'");
        };
    }

    private static String scheme(String url) {
        // Artemis accepts failover lists such as "(tcp://a:61616,tcp://b:61616)?ha=true".
        String first = url.startsWith("(") ? url.substring(1) : url;
        try {
            String scheme = URI.create(first.split("[,)]", 2)[0]).getScheme();
            return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static boolean tlsEnabled(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("sslenabled=true") && !lower.contains("sslenabled=false");
    }

    @Override
    public String toString() {
        return "ArtemisAnalysisSettings[requireTls=" + requireTls + ", retryIntervalMs=" + retryIntervalMs
                + ", callTimeoutMs=" + callTimeoutMs + "]";
    }
}
