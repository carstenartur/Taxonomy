package com.taxonomy.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents deployment-facing configuration from drifting away from the bilingual reference. */
class ConfigurationReferenceContractTest {

    private static final Pattern ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{([A-Z][A-Z0-9_]+)(?::|})");
    private static final Pattern EXPLICIT_PROPERTY_ENV = Pattern.compile(
            "(?m)^\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*=\\s*"
                    + "\\$\\{([A-Z][A-Z0-9_]+)(?::|})");
    private static final Pattern VALUE_PROPERTY = Pattern.compile(
            "@Value\\(\\s*\"\\$\\{([A-Za-z][A-Za-z0-9_.-]*)");
    private static final Pattern CONDITIONAL = Pattern.compile(
            "@ConditionalOnProperty\\s*\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern PREFIX = Pattern.compile(
            "prefix\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile(
            "name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern CONFIGURATION_PROPERTIES = Pattern.compile(
            "@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern CONFIGURATION_FIELD = Pattern.compile(
            "(?m)^\\s*private\\s+(?!static\\b)(?:final\\s+)?[A-Za-z0-9_<>, ?]+\\s+"
                    + "([a-z][A-Za-z0-9]*)\\s*(?:=|;)");
    private static final Pattern DOCUMENTED_VARIABLE = Pattern.compile(
            "(?m)^\\|\\s*`([A-Z][A-Z0-9_]*)`\\s*\\|");

    @Test
    void bothLanguageReferencesCoverExactlyTheSupportedRuntimeVariables() throws Exception {
        Path root = repositoryRoot();
        Set<String> expected = discoverRuntimeVariables(root);
        Set<String> english = documentedVariables(read(root,
                "docs/en/CONFIGURATION_REFERENCE.md"));
        Set<String> german = documentedVariables(read(root,
                "docs/de/CONFIGURATION_REFERENCE.md"));

        assertThat(english).as("English configuration-variable table").isEqualTo(expected);
        assertThat(german).as("German configuration-variable table").isEqualTo(expected);
    }

    @Test
    void architectureNodeLimitAndSecurityDefaultsAreExplainedTruthfully() throws Exception {
        Path root = repositoryRoot();
        String english = read(root, "docs/en/CONFIGURATION_REFERENCE.md");
        String german = read(root, "docs/de/CONFIGURATION_REFERENCE.md");

        assertThat(english)
                .contains("`TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES`")
                .contains("manual Copilot and Autopilot")
                .contains("one-time random bootstrap password")
                .doesNotContain("default password `admin`");
        assertThat(german)
                .contains("`TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES`")
                .contains("manuellen Copilot als auch für den Autopiloten")
                .contains("einmaliges zufälliges Startpasswort")
                .doesNotContain("Standardpasswort `admin`");
    }

    @Test
    void productionExamplesForwardSettingsAndKeepCredentialsSeparate() throws Exception {
        Path root = repositoryRoot();
        String environment = read(root, ".env.example");
        String compose = read(root, "docker-compose.prod.yml");
        String helm = read(root, "deploy/helm/taxonomy/values.yaml");
        String helmValidation = read(root,
                "deploy/helm/taxonomy/templates/validation.yaml");
        String helmReadme = read(root, "deploy/helm/taxonomy/README.md");
        String rancherGuide = read(root, "deploy/helm/taxonomy/RANCHER.md");

        assertThat(environment)
                .contains("LLM_PROVIDER=")
                .doesNotContain("\nLLM_PROVIDER=LOCAL_ONNX\n")
                .contains("TAXONOMY_EMBEDDING_ENABLED=false")
                .contains("TAXONOMY_AI_COPILOT_PROFILE=FULL")
                .contains("TAXONOMY_AI_AUTOPILOT_PROFILE=EXHAUSTIVE")
                .contains("TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50")
                .contains("# ADMIN_PASSWORD=replace-with-a-distinct-random-machine-token");
        assertThat(compose)
                .contains("env_file:\n      - .env")
                .contains("TAXONOMY_EMBEDDING_ENABLED=${TAXONOMY_EMBEDDING_ENABLED:-false}");
        assertThat(helm)
                .contains("TAXONOMY_AI_COPILOT_PROFILE: FULL")
                .contains("TAXONOMY_AI_AUTOPILOT_PROFILE: EXHAUSTIVE")
                .contains("TAXONOMY_AI_AUTOPILOT_PROPOSE_SOLUTIONS: \"true\"")
                .contains("TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_COVERAGE: \"25\"")
                .contains("  TAXONOMY_ADMIN_PASSWORD:\n    key: ADMIN_PASSWORD")
                .contains("  ADMIN_PASSWORD:\n    key: ADMIN_TOKEN")
                .contains("secretKey: ADMIN_TOKEN")
                .doesNotContain("  ADMIN_PASSWORD:\n    key: ADMIN_PASSWORD");
        assertThat(helmValidation)
                .contains("must use different Secret keys")
                .contains("must read the Actuator/admin token from the same key");
        assertThat(helmReadme)
                .contains("--from-literal=ADMIN_TOKEN=")
                .contains("jsonpath='{.data.ADMIN_TOKEN}'")
                .contains("Never reuse the interactive login credential as the machine token");
        assertThat(rancherGuide)
                .contains("--from-literal=ADMIN_TOKEN=")
                .contains("Never reuse the interactive `ADMIN_PASSWORD` value as `ADMIN_TOKEN`")
                .contains("serviceMonitor.enabled=false");
    }

    private static Set<String> discoverRuntimeVariables(Path root) throws IOException {
        Path resources = root.resolve("taxonomy-app/src/main/resources");
        Map<String, String> explicitPropertyVariables = new HashMap<>();
        Set<String> variables = new TreeSet<>(Set.of(
                "SPRING_PROFILES_ACTIVE",
                "SPRING_DATASOURCE_URL"));

        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.filter(ConfigurationReferenceContractTest::isRuntimeProperties)
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher placeholder = ENV_PLACEHOLDER.matcher(source);
                while (placeholder.find()) {
                    variables.add(placeholder.group(1));
                }
                Matcher mapping = EXPLICIT_PROPERTY_ENV.matcher(source);
                while (mapping.find()) {
                    explicitPropertyVariables.put(mapping.group(1), mapping.group(2));
                }
            }
        }

        Path javaRoot = root.resolve("taxonomy-app/src/main/java");
        try (Stream<Path> files = Files.walk(javaRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collectValueProperties(source, explicitPropertyVariables, variables);
                collectConditionalProperties(source, explicitPropertyVariables, variables);
                collectConfigurationProperties(source, explicitPropertyVariables, variables);
            }
        }
        return Set.copyOf(variables);
    }

    private static void collectValueProperties(
            String source,
            Map<String, String> explicitPropertyVariables,
            Set<String> variables) {
        Matcher matcher = VALUE_PROPERTY.matcher(source);
        while (matcher.find()) {
            addProperty(matcher.group(1), explicitPropertyVariables, variables);
        }
    }

    private static void collectConditionalProperties(
            String source,
            Map<String, String> explicitPropertyVariables,
            Set<String> variables) {
        Matcher annotations = CONDITIONAL.matcher(source);
        while (annotations.find()) {
            String body = annotations.group(1);
            Matcher nameMatcher = NAME.matcher(body);
            if (!nameMatcher.find()) continue;
            String name = nameMatcher.group(1);
            String property = name;
            Matcher prefixMatcher = PREFIX.matcher(body);
            if (!name.startsWith("taxonomy.") && prefixMatcher.find()) {
                property = prefixMatcher.group(1) + "." + name;
            }
            addProperty(property, explicitPropertyVariables, variables);
        }
    }

    private static void collectConfigurationProperties(
            String source,
            Map<String, String> explicitPropertyVariables,
            Set<String> variables) {
        Matcher prefixMatcher = CONFIGURATION_PROPERTIES.matcher(source);
        if (!prefixMatcher.find()) return;
        String prefix = prefixMatcher.group(1);
        if (!prefix.startsWith("taxonomy.")) return;

        Matcher fields = CONFIGURATION_FIELD.matcher(source);
        while (fields.find()) {
            String property = prefix + "." + camelToKebab(fields.group(1));
            addProperty(property, explicitPropertyVariables, variables);
        }
    }

    private static void addProperty(
            String property,
            Map<String, String> explicitPropertyVariables,
            Set<String> variables) {
        if (property == null) return;
        if (property.matches("[A-Z][A-Z0-9_]+")) {
            variables.add(property);
            return;
        }
        if (!property.startsWith("taxonomy.")) return;
        variables.add(explicitPropertyVariables.getOrDefault(property, toEnvironmentName(property)));
    }

    private static Set<String> documentedVariables(String document) {
        Set<String> variables = new TreeSet<>();
        Matcher matcher = DOCUMENTED_VARIABLE.matcher(document);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return Set.copyOf(variables);
    }

    private static boolean isRuntimeProperties(Path path) {
        String name = path.getFileName().toString();
        return name.equals("ai-automation-defaults.properties")
                || name.startsWith("application-") && name.endsWith(".properties")
                || name.equals("application.properties");
    }

    private static String camelToKebab(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String toEnvironmentName(String property) {
        return property.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String read(Path root, String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("taxonomy-app"))
                    && Files.isDirectory(current.resolve("docs/de"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
