package com.taxonomy;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchets direct class dependencies between the bounded contexts that are
 * candidates for extraction from {@code taxonomy-app}.
 *
 * <p>The baseline is intentionally structural rather than a list of currently
 * "allowed" directions. Any new cross-context class edge, any growth of an
 * existing package-to-package edge, and any reduction that has not yet been
 * recorded in the baseline fails the test. This keeps improvements monotonic
 * while the physical Maven-module extraction is performed incrementally.</p>
 *
 * <p>Every production Java package below the application package root must also
 * be classified by the checked context map. Root-package composition classes
 * are listed explicitly and belong to the composition context. This prevents
 * new code or composition wiring from evading the ratchet through an unmapped
 * package or root-level class.</p>
 */
class ArchitectureContextDependencyRatchetTest {

    private static final Comparator<PackageEdge> EDGE_ORDER = Comparator
            .comparing(PackageEdge::fromContext)
            .thenComparing(PackageEdge::fromPackage)
            .thenComparing(PackageEdge::toContext)
            .thenComparing(PackageEdge::toPackage);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void managedContextDependenciesMatchReviewedBaseline() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        ContextPolicy policy = readAndValidateContextPolicy(
                repositoryRoot.resolve(".github/architecture-contexts.json"));
        validateSourceCoverage(repositoryRoot, policy);

        SortedMap<PackageEdge, Integer> actual = collectDependencies(policy);
        Path baselinePath = repositoryRoot.resolve(".github/architecture-dependency-baseline.json");
        if (!Files.isRegularFile(baselinePath)) {
            throw new AssertionError("Architecture dependency baseline is missing. Review and add this generated baseline:\n"
                    + renderBaseline(actual));
        }

        SortedMap<PackageEdge, Integer> expected = readBaseline(baselinePath, policy.contexts());
        List<String> differences = compare(expected, actual);
        assertThat(differences)
                .withFailMessage(() -> "Cross-context dependency ratchet changed:\n- "
                        + String.join("\n- ", differences)
                        + "\n\nIf the change is intentional, review the dependency direction and update the baseline to:\n"
                        + renderBaseline(actual))
                .isEmpty();
    }

    private ContextPolicy readAndValidateContextPolicy(Path policyPath) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(policyPath));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        String compositionModule = requiredText(root, "compositionModule");
        assertThat(compositionModule).isEqualTo("taxonomy-app");
        assertThat(root.path("catchAllAdapterModuleAllowed").isBoolean()).isTrue();
        assertThat(root.path("catchAllAdapterModuleAllowed").asBoolean()).isFalse();

        JsonNode rootClassNodes = root.path("rootCompositionClasses");
        assertThat(rootClassNodes.isArray()).isTrue();
        Set<String> rootCompositionClasses = new LinkedHashSet<>();
        for (JsonNode rootClassNode : rootClassNodes) {
            String fileName = rootClassNode.asText();
            assertThat(fileName).as("root composition class file").isNotBlank().endsWith(".java");
            assertThat(fileName).as("root composition class must be a file name").doesNotContain("/", "\\");
            assertThat(rootCompositionClasses.add(fileName))
                    .as("duplicate root composition class %s", fileName)
                    .isTrue();
        }
        assertThat(rootCompositionClasses).isNotEmpty();

        JsonNode contextNodes = root.path("contexts");
        assertThat(contextNodes.isArray()).isTrue();

        List<ContextDefinition> contexts = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> targetModules = new LinkedHashSet<>();
        Set<String> patterns = new LinkedHashSet<>();
        for (JsonNode contextNode : contextNodes) {
            String id = requiredText(contextNode, "id");
            assertThat(ids.add(id)).as("duplicate architecture context %s", id).isTrue();

            JsonNode targetModuleNode = contextNode.path("targetModule");
            String targetModule = targetModuleNode.isNull() || targetModuleNode.isMissingNode()
                    ? null : targetModuleNode.asText();
            if (targetModule != null) {
                assertThat(targetModule).as("target module for %s", id).isNotBlank();
                assertThat(targetModules.add(targetModule))
                        .as("target module %s must belong to only one context", targetModule)
                        .isTrue();
            }

            JsonNode packageNodes = contextNode.path("packages");
            assertThat(packageNodes.isArray()).as("packages for %s", id).isTrue();
            List<String> contextPatterns = new ArrayList<>();
            for (JsonNode packageNode : packageNodes) {
                String pattern = packageNode.asText();
                assertThat(pattern)
                        .as("package pattern for %s must be a subtree pattern", id)
                        .startsWith("com.taxonomy.")
                        .endsWith("..");
                assertThat(patterns.add(pattern)).as("duplicate package pattern %s", pattern).isTrue();
                contextPatterns.add(pattern);
            }
            assertThat(contextPatterns).as("packages for %s", id).isNotEmpty();
            contexts.add(new ContextDefinition(id, targetModule, List.copyOf(contextPatterns)));
        }

        for (int left = 0; left < contexts.size(); left++) {
            for (int right = left + 1; right < contexts.size(); right++) {
                for (String leftPattern : contexts.get(left).packages()) {
                    for (String rightPattern : contexts.get(right).packages()) {
                        assertThat(patternsOverlap(leftPattern, rightPattern))
                                .as("context package patterns must not overlap: %s and %s",
                                        leftPattern, rightPattern)
                                .isFalse();
                    }
                }
            }
        }

        List<ContextDefinition> compositionContexts = contexts.stream()
                .filter(context -> compositionModule.equals(context.targetModule()))
                .toList();
        assertThat(compositionContexts)
                .as("exactly one context must own the composition module %s", compositionModule)
                .hasSize(1);
        return new ContextPolicy(
                List.copyOf(contexts),
                Set.copyOf(rootCompositionClasses),
                compositionContexts.getFirst());
    }

    private static void validateSourceCoverage(Path repositoryRoot, ContextPolicy policy) throws Exception {
        Path packageRoot = repositoryRoot.resolve("taxonomy-app/src/main/java/com/taxonomy");
        assertThat(Files.isDirectory(packageRoot)).as("taxonomy-app production package root").isTrue();

        Set<String> actualRootJavaFiles;
        try (var rootSources = Files.list(packageRoot)) {
            actualRootJavaFiles = rootSources
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".java"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
        assertThat(actualRootJavaFiles)
                .withFailMessage(() -> "Root-package Java classes must be explicitly listed as composition classes in "
                        + ".github/architecture-contexts.json. Expected " + policy.rootCompositionClasses()
                        + " but found " + actualRootJavaFiles)
                .containsExactlyInAnyOrderElementsOf(policy.rootCompositionClasses());

        List<String> unclassifiedPackages;
        try (var sources = Files.walk(packageRoot)) {
            unclassifiedPackages = sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::getParent)
                    .map(packageRoot::relativize)
                    .map(Path::toString)
                    .filter(relativePackage -> !relativePackage.isEmpty())
                    .map(relativePackage -> "com.taxonomy." + relativePackage.replace('\\', '.').replace('/', '.'))
                    .filter(packageName -> contextFor(packageName, policy.contexts()) == null)
                    .distinct()
                    .sorted()
                    .toList();
        }

        assertThat(unclassifiedPackages)
                .withFailMessage(() -> "Production packages are missing from .github/architecture-contexts.json: "
                        + String.join(", ", unclassifiedPackages))
                .isEmpty();
    }

    private SortedMap<PackageEdge, Integer> collectDependencies(ContextPolicy policy) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.taxonomy");

        Map<PackageEdge, Set<ClassPair>> classPairsByEdge = new TreeMap<>(EDGE_ORDER);
        for (JavaClass origin : classes) {
            ContextDefinition originContext = contextFor(origin, policy);
            if (originContext == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                ContextDefinition targetContext = contextFor(target, policy);
                if (targetContext == null || originContext.id().equals(targetContext.id())) {
                    continue;
                }
                PackageEdge edge = new PackageEdge(
                        originContext.id(), origin.getPackageName(),
                        targetContext.id(), target.getPackageName());
                classPairsByEdge.computeIfAbsent(edge, ignored -> new HashSet<>())
                        .add(new ClassPair(origin.getName(), target.getName()));
            }
        }

        SortedMap<PackageEdge, Integer> counts = new TreeMap<>(EDGE_ORDER);
        classPairsByEdge.forEach((edge, classPairs) -> counts.put(edge, classPairs.size()));
        return counts;
    }

    private SortedMap<PackageEdge, Integer> readBaseline(
            Path baselinePath, List<ContextDefinition> contexts) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(baselinePath));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        JsonNode edgeNodes = root.path("edges");
        assertThat(edgeNodes.isArray()).isTrue();

        Set<String> contextIds = new HashSet<>();
        contexts.forEach(context -> contextIds.add(context.id()));
        SortedMap<PackageEdge, Integer> baseline = new TreeMap<>(EDGE_ORDER);
        for (JsonNode edgeNode : edgeNodes) {
            PackageEdge edge = new PackageEdge(
                    requiredText(edgeNode, "fromContext"),
                    requiredText(edgeNode, "fromPackage"),
                    requiredText(edgeNode, "toContext"),
                    requiredText(edgeNode, "toPackage"));
            assertThat(contextIds).as("known source context for %s", edge).contains(edge.fromContext());
            assertThat(contextIds).as("known target context for %s", edge).contains(edge.toContext());
            assertThat(edge.fromContext()).as("cross-context baseline edge %s", edge)
                    .isNotEqualTo(edge.toContext());
            int count = edgeNode.path("classDependencyCount").asInt();
            assertThat(count).as("positive dependency count for %s", edge).isPositive();
            assertThat(baseline.put(edge, count)).as("duplicate baseline edge %s", edge).isNull();
        }
        return baseline;
    }

    private static List<String> compare(
            SortedMap<PackageEdge, Integer> expected, SortedMap<PackageEdge, Integer> actual) {
        Set<PackageEdge> allEdges = new TreeSet<>(EDGE_ORDER);
        allEdges.addAll(expected.keySet());
        allEdges.addAll(actual.keySet());

        List<String> differences = new ArrayList<>();
        for (PackageEdge edge : allEdges) {
            Integer previous = expected.get(edge);
            Integer current = actual.get(edge);
            if (previous == null) {
                differences.add("NEW " + describe(edge) + ": 0 -> " + current);
            } else if (current == null) {
                differences.add("IMPROVED " + describe(edge) + ": " + previous
                        + " -> 0; lower the reviewed baseline so the dependency cannot return");
            } else if (current > previous) {
                differences.add("GREW " + describe(edge) + ": " + previous + " -> " + current);
            } else if (current < previous) {
                differences.add("IMPROVED " + describe(edge) + ": " + previous + " -> " + current
                        + "; lower the reviewed baseline so the dependency cannot regrow");
            }
        }
        return differences;
    }

    private static ContextDefinition contextFor(JavaClass javaClass, ContextPolicy policy) {
        ContextDefinition packageContext = contextFor(javaClass.getPackageName(), policy.contexts());
        if (packageContext != null || !"com.taxonomy".equals(javaClass.getPackageName())) {
            return packageContext;
        }

        String binaryName = javaClass.getName().substring("com.taxonomy.".length());
        int nestedSeparator = binaryName.indexOf('$');
        String sourceFile = (nestedSeparator < 0 ? binaryName : binaryName.substring(0, nestedSeparator)) + ".java";
        return policy.rootCompositionClasses().contains(sourceFile) ? policy.compositionContext() : null;
    }

    private static ContextDefinition contextFor(String packageName, List<ContextDefinition> contexts) {
        ContextDefinition match = null;
        for (ContextDefinition context : contexts) {
            for (String pattern : context.packages()) {
                if (matches(pattern, packageName)) {
                    if (match != null && !match.id().equals(context.id())) {
                        throw new IllegalStateException("Package " + packageName
                                + " matches both architecture contexts " + match.id() + " and " + context.id());
                    }
                    match = context;
                }
            }
        }
        return match;
    }

    private static boolean matches(String pattern, String packageName) {
        String prefix = pattern.substring(0, pattern.length() - 2);
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    private static boolean patternsOverlap(String left, String right) {
        String leftPrefix = left.substring(0, left.length() - 2);
        String rightPrefix = right.substring(0, right.length() - 2);
        return leftPrefix.equals(rightPrefix)
                || leftPrefix.startsWith(rightPrefix + ".")
                || rightPrefix.startsWith(leftPrefix + ".");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        assertThat(value).as("field %s must be present and non-blank", field).isNotBlank();
        return value;
    }

    private static String renderBaseline(SortedMap<PackageEdge, Integer> counts) {
        StringBuilder result = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"edges\": [\n");
        int index = 0;
        for (Map.Entry<PackageEdge, Integer> entry : counts.entrySet()) {
            PackageEdge edge = entry.getKey();
            if (index++ > 0) {
                result.append(",\n");
            }
            result.append("    {\n")
                    .append("      \"fromContext\": \"").append(edge.fromContext()).append("\",\n")
                    .append("      \"fromPackage\": \"").append(edge.fromPackage()).append("\",\n")
                    .append("      \"toContext\": \"").append(edge.toContext()).append("\",\n")
                    .append("      \"toPackage\": \"").append(edge.toPackage()).append("\",\n")
                    .append("      \"classDependencyCount\": ").append(entry.getValue()).append("\n")
                    .append("    }");
        }
        return result.append("\n  ]\n}\n").toString();
    }

    private static String describe(PackageEdge edge) {
        return edge.fromContext() + "/" + edge.fromPackage()
                + " -> " + edge.toContext() + "/" + edge.toPackage();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".github/architecture-contexts.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root");
    }

    private record ContextPolicy(
            List<ContextDefinition> contexts,
            Set<String> rootCompositionClasses,
            ContextDefinition compositionContext) {}

    private record ContextDefinition(String id, String targetModule, List<String> packages) {}

    private record PackageEdge(String fromContext, String fromPackage, String toContext, String toPackage) {}

    private record ClassPair(String originClass, String targetClass) {}
}
