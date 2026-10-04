package com.taxonomy.composition.analysis.artemis;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        for (URI connector : connectors(brokerUrl, requireTls)) {
            String scheme = connector.getScheme().toLowerCase(Locale.ROOT);
            if (requireTls && scheme.equals("tcp") && !tlsEnabled(connector.getRawQuery())) {
                throw tlsRequired();
            }
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

    private static List<URI> connectors(String url, boolean requireTls) {
        String endpoints = url;
        try {
            if (url.startsWith("(")) {
                int end = url.indexOf(')');
                if (end < 2 || end != url.lastIndexOf(')') || url.indexOf('(', 1) >= 0) {
                    throw invalidUrl();
                }
                endpoints = url.substring(1, end);
                String suffix = url.substring(end + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith("?")) throw invalidUrl();
                    URI options = URI.create("tcp://validation" + suffix);
                    if (options.getRawFragment() != null) throw invalidUrl();
                    // Connection-wide options must not turn off an explicitly secured
                    // connector. A global true still cannot secure an unconfigured one.
                    if (requireTls && hasTlsOption(options.getRawQuery())
                            && !tlsEnabled(options.getRawQuery())) throw tlsRequired();
                }
            } else if (url.indexOf(',') >= 0) {
                // Artemis only expands comma-separated connectors inside parentheses.
                // Otherwise the comma may be part of the first connector's TLS value.
                throw invalidUrl();
            }
            var connectors = new ArrayList<URI>();
            for (String endpoint : endpoints.split(",", -1)) {
                URI connector = URI.create(endpoint);
                String scheme = connector.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("tcp") && !scheme.equalsIgnoreCase("vm"))
                        || connector.getHost() == null || connector.getRawFragment() != null) {
                    throw invalidUrl();
                }
                connectors.add(connector);
            }
            return List.copyOf(connectors);
        } catch (IllegalArgumentException invalid) {
            // URI/parser exceptions may contain user-info or credential query values.
            throw invalidUrl();
        }
    }

    private static boolean hasTlsOption(String query) {
        return tlsValues(query).size() > 0;
    }

    private static boolean tlsEnabled(String query) {
        List<String> values = tlsValues(query);
        return values.size() == 1 && values.getFirst().equals("true");
    }

    private static List<String> tlsValues(String query) {
        var values = new ArrayList<String>();
        if (query != null) {
            for (String parameter : query.split("&", -1)) {
                String[] pair = parameter.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (name.equals("sslEnabled")) {
                    values.add(pair.length == 2
                            ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
            }
        }
        return values;
    }

    private static IllegalStateException tlsRequired() {
        return new IllegalStateException("taxonomy.analysis.artemis.broker-url must set sslEnabled=true "
                + "exactly once on every TCP connector "
                + "(or set taxonomy.analysis.artemis.require-tls=false for an isolated network)");
    }

    private static IllegalStateException invalidUrl() {
        return new IllegalStateException("taxonomy.analysis.artemis.broker-url must contain valid tcp:// or vm:// "
                + "connectors, optionally in a parenthesized failover list; fragments are not supported");
    }

    @Override
    public String toString() {
        return "ArtemisAnalysisSettings[requireTls=" + requireTls + ", retryIntervalMs=" + retryIntervalMs
                + ", callTimeoutMs=" + callTimeoutMs + "]";
    }
}
