package com.taxonomy.setup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Side-effect-free checks over effective Spring property names, not a second config store. */
public final class SetupChecks {
    public enum Status { OK, WARNING, ERROR, NOT_CHECKED }

    /** Findings deliberately contain only fixed messages and property names, never values. */
    public record Finding(Status status, String key, String message) { }

    private SetupChecks() { }

    public static List<Finding> check(Function<String, String> properties, Set<String> profiles) {
        List<Finding> result = new ArrayList<>();
        String url = value(properties, "spring.datasource.url");
        boolean memory = url.startsWith("jdbc:hsqldb:mem:");
        boolean known = url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:hsqldb:")
                || url.startsWith("jdbc:sqlserver:") || url.startsWith("jdbc:oracle:");
        if (!known) {
            error(result, "spring.datasource.url", "Select a supported JDBC URL and matching database profile.");
        } else if (memory) {
            result.add(new Finding(profiles.contains("production") || profiles.contains("kubernetes")
                    ? Status.ERROR : Status.WARNING, "spring.datasource.url",
                    "In-memory data is lost at shutdown; do not use this configuration for persistent operation."));
        }
        String ddl = value(properties, "spring.jpa.hibernate.ddl-auto");
        if (!memory && Set.of("create", "create-drop", "drop").contains(ddl.toLowerCase(Locale.ROOT))) {
            error(result, "spring.jpa.hibernate.ddl-auto", "Destructive schema creation is not allowed for a persistent installation.");
        }
        if (profiles.contains("keycloak")) {
            if (profiles.contains("local-user-management")) {
                error(result, "spring.profiles.active", "Choose central login or local user management, not both.");
            }
            if ("true".equalsIgnoreCase(value(properties, "taxonomy.security.local-users-enabled"))) {
                error(result, "taxonomy.security.local-users-enabled", "Local user administration must remain disabled with Keycloak.");
            }
            endpoint(result, properties, "spring.security.oauth2.client.provider.keycloak.issuer-uri");
            required(result, properties, "spring.security.oauth2.client.registration.keycloak.client-id");
            required(result, properties, "spring.security.oauth2.client.registration.keycloak.client-secret");
            result.add(new Finding(Status.NOT_CHECKED, "authentication.roles",
                    "Verify a real login and ROLE_USER/ROLE_ARCHITECT/ROLE_ADMIN mapping at the identity provider."));
        } else {
            result.add(new Finding(Status.NOT_CHECKED, "authentication.bootstrap",
                    "Check initial administrator provisioning or an existing administrator; no account is created by this check."));
        }
        String port = value(properties, "server.port");
        if (!port.isEmpty()) {
            try {
                int number = Integer.parseInt(port);
                if (number < 1 || number > 65535) { throw new NumberFormatException(); }
            } catch (NumberFormatException invalid) {
                error(result, "server.port", "Use a port between 1 and 65535.");
            }
        }
        for (String key : List.of("embedding.enabled", "embedding.allow-download")) {
            String setting = value(properties, key);
            if (!setting.isEmpty() && !Set.of("true", "false").contains(setting.toLowerCase(Locale.ROOT))) {
                error(result, key, "Use true or false.");
            }
        }
        String provider = value(properties, "llm.provider").toUpperCase(Locale.ROOT);
        switch (provider) {
            case "", "LOCAL_ONNX" -> { }
            case "CUSTOM_OPENAI" -> {
                endpoint(result, properties, "custom.llm.url");
                required(result, properties, "custom.llm.model");
            }
            case "GEMINI", "OPENAI", "DEEPSEEK", "QWEN", "LLAMA", "MISTRAL" ->
                    required(result, properties, provider.toLowerCase(Locale.ROOT) + ".api.key");
            default -> error(result, "llm.provider", "Choose a supported provider; no provider is configured by this check.");
        }
        boolean embeddings = Boolean.parseBoolean(value(properties, "embedding.enabled"));
        if (provider.equals("LOCAL_ONNX") && !embeddings) {
            error(result, "embedding.enabled", "LOCAL_ONNX requires explicitly enabled embeddings.");
        }
        if (embeddings && value(properties, "embedding.model.dir").isEmpty()
                && !Boolean.parseBoolean(value(properties, "embedding.allow-download"))) {
            error(result, "embedding.model.dir", "Provide a local model directory or explicitly permit downloads.");
        }
        result.add(new Finding(Status.NOT_CHECKED, "ai.inference", "No inference, paid request or model download is performed."));
        result.add(new Finding(Status.NOT_CHECKED, "database.schema", "No schema migration, write-permission test or rollback test is performed."));
        if (result.stream().noneMatch(f -> f.status() == Status.ERROR)) {
            result.add(new Finding(Status.OK, "configuration.static", "Supported static configuration checks passed; this is not an installation acceptance test."));
        }
        return List.copyOf(result);
    }

    static String value(Function<String, String> properties, String key) {
        String result = properties.apply(key);
        return result == null ? "" : result.trim();
    }

    static boolean safeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(host == null ? "" : host.toLowerCase(Locale.ROOT));
            return host != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void endpoint(List<Finding> result, Function<String, String> properties, String key) {
        if (!safeEndpoint(value(properties, key))) {
            error(result, key, "Use HTTPS (HTTP only on loopback), without URL credentials, query or fragment.");
        }
    }

    private static void required(List<Finding> result, Function<String, String> properties, String key) {
        if (value(properties, key).isEmpty()) { error(result, key, "A non-empty setting or Secret reference is required."); }
    }

    private static void error(List<Finding> result, String key, String message) {
        result.add(new Finding(Status.ERROR, key, message));
    }
}
